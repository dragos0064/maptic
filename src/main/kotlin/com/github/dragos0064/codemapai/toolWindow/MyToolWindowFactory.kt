package com.github.dragos0064.codemapai.toolWindow

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.event.TreeSelectionListener
import java.awt.Dimension
import java.net.HttpURLConnection
import java.net.URL
import com.intellij.openapi.fileEditor.FileEditorManager
import java.util.concurrent.*
import org.json.JSONObject
import com.intellij.openapi.util.IconLoader
import java.awt.FlowLayout
import java.io.File
import java.util.Properties

class MyToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = CodeMapToolWindow(project)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
        // Schedule the update so it happens after the tool window is fully initialized.
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            myToolWindow.updateFavicon(toolWindow, "myIcon.png")
        }
    }

    override fun shouldBeAvailable(project: Project) = true

    class CodeMapToolWindow(private val project: Project) {

        data class NodeData(val label: String, val element: PsiElement) {
            override fun toString(): String = label
        }

        // Data class to hold the full code structure.
        private data class CodeStructure(
            val topLevelClasses: List<PsiClass>,
            val topLevelMethods: List<PsiMethod>,
            val classChildren: Map<PsiClass, List<PsiClass>>,
            val classMethods: Map<PsiClass, List<PsiMethod>>
        )

        /**
         * Updates the tool window's icon (favicon) using an icon from the resources.
         * Ensure that the icon file (e.g., "myIcon.png") is in src/main/resources/icons/.
         */
        fun updateFavicon(toolWindow: ToolWindow, iconName: String) {
            val icon = IconLoader.getIcon("/icons/$iconName", javaClass)
            toolWindow.setIcon(icon)
        }

        /**
         * Loads the API key from the external file "api_key.env".
         * The file should contain a line like: OPENAI_API_KEY=your_actual_api_key_here
         */
        private fun loadApiKey(): String? {
            val envFile = File("C:/Users/drago/codemapai/.env")
            if (!envFile.exists()) return null
            val properties = Properties()
            envFile.inputStream().use { properties.load(it) }
            return properties.getProperty("OPENAI_API_KEY")
        }

        /**
         * Creates the main panel. This panel has:
         *  1) A top bar with a small refresh button.
         *  2) The rest of the UI (trees) in a separate panel.
         */
        fun getContent(): JPanel {
            val mainPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }

            // Create a top bar for the small refresh button.
            val topBar = JPanel().apply {
                layout = FlowLayout(FlowLayout.LEFT, 5, 5)
                maximumSize = Dimension(Int.MAX_VALUE, 40) // keep it short in height
            }

            val refreshButton = JButton("🔄").apply {
                preferredSize = Dimension(24, 24)
                maximumSize = Dimension(24, 24)
                toolTipText = "Refresh"
            }

            // When clicked, rebuild the trees panel.
            refreshButton.addActionListener {
                mainPanel.removeAll()
                mainPanel.add(topBar)              // re-add the top bar
                mainPanel.add(buildTreesPanel())    // re-add a fresh trees panel
                mainPanel.revalidate()
                mainPanel.repaint()
            }

            topBar.add(refreshButton)
            mainPanel.add(topBar)

            // Now add the actual trees panel below the top bar.
            mainPanel.add(buildTreesPanel())

            return mainPanel
        }

        /**
         * Builds the code-structure and description trees and returns them in a panel.
         * This can be recreated each time the user clicks refresh.
         */
        private fun buildTreesPanel(): JPanel {
            val panel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }

            val codeStructure = getElementsTree()
            val structureRoot = DefaultMutableTreeNode("📦 Code Structure")
            val descriptionRoot = DefaultMutableTreeNode("🦖 Code Descriptions")

            val executor = Executors.newFixedThreadPool(4)
            val futures = mutableMapOf<String, Future<String>>()

            // Recursively collect summaries for each class and its methods.
            fun collectClassAndMethods(cls: PsiClass) {
                val classText = ReadAction.compute<String, Throwable> { cls.text }
                futures[classText] = executor.submit(Callable { getOpenAISummary(classText) })

                codeStructure.classMethods[cls]?.forEach { method ->
                    val methodText = ReadAction.compute<String, Throwable> { method.text }
                    futures[methodText] = executor.submit(Callable { getOpenAISummary(methodText) })
                }

                codeStructure.classChildren[cls]?.forEach { childCls ->
                    collectClassAndMethods(childCls)
                }
            }

            // Collect for all top-level classes.
            codeStructure.topLevelClasses.forEach { collectClassAndMethods(it) }

            // Collect for top-level methods (methods not in any class).
            codeStructure.topLevelMethods.forEach { method ->
                val methodText = ReadAction.compute<String, Throwable> { method.text }
                futures[methodText] = executor.submit(Callable { getOpenAISummary(methodText) })
            }

            executor.shutdown()
            executor.awaitTermination(60, TimeUnit.SECONDS)

            // Recursively build tree nodes for a given class.
            fun buildClassNode(cls: PsiClass): Pair<DefaultMutableTreeNode, DefaultMutableTreeNode> {
                val className = ReadAction.compute<String, Throwable> { cls.name ?: "UnnamedClass" }
                val classText = ReadAction.compute<String, Throwable> { cls.text }
                val classDesc = futures[classText]?.get() ?: "❌ Failed to fetch"

                val structureNode = DefaultMutableTreeNode(NodeData("📦 $className", cls))
                val descriptionNode = DefaultMutableTreeNode(classDesc)

                // Add methods of the class.
                codeStructure.classMethods[cls]?.forEach { method ->
                    val methodName = ReadAction.compute<String, Throwable> { method.name ?: "Unnamed" }
                    val methodText = ReadAction.compute<String, Throwable> { method.text }
                    structureNode.add(DefaultMutableTreeNode(NodeData("🔹 $methodName()", method)))
                    val methodDesc = futures[methodText]?.get() ?: "❌ Failed to fetch"
                    descriptionNode.add(DefaultMutableTreeNode(methodDesc))
                }

                // Recursively add child classes.
                codeStructure.classChildren[cls]?.forEach { childCls ->
                    val (childStructNode, childDescNode) = buildClassNode(childCls)
                    structureNode.add(childStructNode)
                    descriptionNode.add(childDescNode)
                }
                return structureNode to descriptionNode
            }

            // Build tree nodes for top-level classes.
            codeStructure.topLevelClasses.forEach { cls ->
                val (structNode, descNode) = buildClassNode(cls)
                structureRoot.add(structNode)
                descriptionRoot.add(descNode)
            }

            // Build nodes for top-level methods (those not within a class).
            codeStructure.topLevelMethods.forEach { method ->
                val methodName = ReadAction.compute<String, Throwable> { method.name ?: "Unnamed" }
                val methodText = ReadAction.compute<String, Throwable> { method.text }
                structureRoot.add(DefaultMutableTreeNode(NodeData("🔹 $methodName()", method)))
                val desc = futures[methodText]?.get() ?: "❌ Failed to fetch"
                descriptionRoot.add(DefaultMutableTreeNode(desc))
            }

            val structureTree = JTree(DefaultTreeModel(structureRoot)).apply {
                isRootVisible = true
                addTreeSelectionListener(TreeSelectionListener {
                    val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@TreeSelectionListener
                    val data = node.userObject as? NodeData ?: return@TreeSelectionListener
                    navigateToElement(data.element)
                })
            }

            val descriptionTree = JTree(DefaultTreeModel(descriptionRoot)).apply {
                isRootVisible = true
            }

            // Add the trees to our panel.
            panel.add(JBScrollPane(structureTree).apply { preferredSize = Dimension(300, 300) })
            panel.add(Box.createRigidArea(Dimension(0, 10)))
            panel.add(JBScrollPane(descriptionTree).apply { preferredSize = Dimension(300, 300) })

            return panel
        }

        // Returns the entire code structure with top-level classes, their nested children, and methods.
        private fun getElementsTree(): CodeStructure {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
                ?: return CodeStructure(emptyList(), emptyList(), emptyMap(), emptyMap())
            val document = editor.document
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
                ?: return CodeStructure(emptyList(), emptyList(), emptyMap(), emptyMap())

            // Find all classes and methods in the file.
            val allClasses = PsiTreeUtil.findChildrenOfType(psiFile, PsiClass::class.java)
            val allMethods = PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java)

            // Build map of parent -> child classes and list of top-level classes.
            val parentToChildren = mutableMapOf<PsiClass, MutableList<PsiClass>>()
            val topLevelClasses = mutableListOf<PsiClass>()

            for (cls in allClasses) {
                val parent = PsiTreeUtil.getParentOfType(cls, PsiClass::class.java)
                if (parent != null) {
                    parentToChildren.computeIfAbsent(parent) { mutableListOf() }.add(cls)
                } else {
                    topLevelClasses.add(cls)
                }
            }

            // Map each class to its methods; also collect methods not inside any class.
            val classToMethods = mutableMapOf<PsiClass, MutableList<PsiMethod>>()
            val topLevelMethods = mutableListOf<PsiMethod>()

            for (method in allMethods) {
                val parentClass = PsiTreeUtil.getParentOfType(method, PsiClass::class.java)
                if (parentClass != null) {
                    classToMethods.computeIfAbsent(parentClass) { mutableListOf() }.add(method)
                } else {
                    topLevelMethods.add(method)
                }
            }

            return CodeStructure(
                topLevelClasses,
                topLevelMethods,
                parentToChildren.mapValues { it.value.toList() },
                classToMethods.mapValues { it.value.toList() }
            )
        }

        private fun navigateToElement(element: PsiElement) {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
            val offset = element.textOffset
            val pos = editor.offsetToLogicalPosition(offset)
            editor.caretModel.moveToLogicalPosition(pos)
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
        }

        private fun getOpenAISummary(code: String): String {
            val apiKey = loadApiKey() ?: return "❌ No API key set"
            val url = URL("https://api.openai.com/v1/chat/completions")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val message = JSONObject()
                .put("role", "user")
                .put("content", "Explain this function or class shortly:\n$code")

            val messages = org.json.JSONArray().put(
                JSONObject().put("role", "system").put("content", "You are a helpful assistant that explains code very shortly, in 1-2 sentences.")
            ).put(message)

            val requestBody = JSONObject()
                .put("model", "gpt-3.5-turbo")
                .put("messages", messages)
                .put("temperature", 0.5)

            return try {
                connection.outputStream.use {
                    it.write(requestBody.toString().toByteArray(Charsets.UTF_8))
                }
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            } catch (e: Exception) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                "❌ API Error: ${e.message}\n$error"
            }
        }
    }
}
