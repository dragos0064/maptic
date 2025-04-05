package com.github.dragos0064.codemapai.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.event.TreeSelectionListener
import java.awt.Dimension
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.LogicalPosition

class MyToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = CodeMapToolWindow(project)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    class CodeMapToolWindow(private val project: Project) {
        fun getContent(): JPanel {
            return JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                val (elementsMap, flatList) = getElementsTree()
                val root = DefaultMutableTreeNode("📦 Code Structure")

                for ((cls, methods) in elementsMap) {
                    val classNode = DefaultMutableTreeNode("📦 ${cls.name}").apply {
                        userObject = cls
                    }
                    methods.forEach { method ->
                        classNode.add(DefaultMutableTreeNode("🔹 ${method.name}()").apply {
                            userObject = method
                        })
                    }
                    root.add(classNode)
                }

                if (elementsMap.isEmpty() && flatList.isNotEmpty()) {
                    flatList.forEach { method ->
                        root.add(DefaultMutableTreeNode("🔹 ${method.name}()").apply {
                            userObject = method
                        })
                    }
                }

                val treeModel = DefaultTreeModel(root)
                val tree = JTree(treeModel)
                tree.isRootVisible = true
                tree.addTreeSelectionListener(TreeSelectionListener {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@TreeSelectionListener
                    val psi = node.userObject
                    if (psi is PsiElement) {
                        navigateToElement(psi)
                    }
                })

                val scrollPane = JBScrollPane(tree)
                scrollPane.preferredSize = Dimension(300, 400)
                add(scrollPane)

                val refreshButton = JButton("🔄 Refresh")
                refreshButton.addActionListener {
                    removeAll()
                    add(getContent())
                    revalidate()
                    repaint()
                }
                add(refreshButton)
            }
        }

        private fun getElementsTree(): Pair<Map<PsiClass, List<PsiMethod>>, List<PsiMethod>> {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return emptyMap<PsiClass, List<PsiMethod>>() to emptyList()
            val document = editor.document
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return emptyMap<PsiClass, List<PsiMethod>>() to emptyList()

            val classes = PsiTreeUtil.findChildrenOfType(psiFile, PsiClass::class.java)
            val methods = PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java)

            val map = mutableMapOf<PsiClass, MutableList<PsiMethod>>()
            val topLevel = mutableListOf<PsiMethod>()

            methods.forEach { method ->
                val parentClass = PsiTreeUtil.getParentOfType(method, PsiClass::class.java)
                if (parentClass != null) {
                    map.computeIfAbsent(parentClass) { mutableListOf() }.add(method)
                } else {
                    topLevel.add(method)
                }
            }

            return map to topLevel
        }

        private fun navigateToElement(element: PsiElement) {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
            val offset = element.textOffset
            val pos = editor.offsetToLogicalPosition(offset)
            editor.caretModel.moveToLogicalPosition(pos)
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
        }
    }
}
