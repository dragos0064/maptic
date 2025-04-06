package com.github.dragos0064.codemapai.toolWindow

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import org.json.JSONObject
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.concurrent.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import java.awt.geom.Arc2D
import java.awt.geom.Path2D

class MyToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val codeMapToolWindow = CodeMapToolWindow(project)
        val contentPanel = codeMapToolWindow.getMainContent()
        val content = ContentFactory.getInstance().createContent(contentPanel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    class CodeMapToolWindow(private val project: Project) {

        private fun loadApiKey(): String? {
            val envFile = File("C:\\Robi\\fac\\projects\\codemapai\\.env")
            if (!envFile.exists()) return null
            val properties = Properties()
            envFile.inputStream().use { properties.load(it) }
            return properties.getProperty("OPENAI_API_KEY")
        }

        data class CodeStructure(
            val topLevelClasses: List<PsiClass>,
            val topLevelMethods: List<PsiMethod>,
            val classChildren: Map<PsiClass, List<PsiClass>>,
            val classMethods: Map<PsiClass, List<PsiMethod>>
        )

        data class NodeData(val label: String, val element: PsiElement) {
            override fun toString(): String = label
        }

        data class BubbleData(
            val name: String,
            val explanation: String,
            val children: List<BubbleData> = emptyList()
        )

        class BubbleComponent(val data: BubbleData) : JPanel() {
            // Fixed component size so that other bubbles don’t move.
            private val componentWidth = 250
            private val componentHeight = 300

            // Bubble dimensions.
            private val bubbleWidth = 200
            private val bubbleHeight = 80

            // We want the bubble to start at the top.
            // Use a small top margin, which also defines the bubble’s starting Y.
            private val topMargin = 10
            private val bubbleBaseY = topMargin

            // Animation state: the bubble’s vertical offset from bubbleBaseY.
            private var animationOffset = 0
            // Target offset: how far down the bubble should move when fully hovered,
            // which is determined by the height of the explanation text plus a small gap.
            private var targetOffset = 0
            private var timer: Timer? = null
            private var isHovered = false
            private val animationStep = 6
            private val animationDelay = 10
            // Gap between the bottom of the text and the bubble’s top.
            private val textMargin = 5

            // Fixed horizontal padding for drawing.
            private val horizontalPadding = 10

            init {
                preferredSize = Dimension(componentWidth, componentHeight)
                isOpaque = false
                // Fixed size so that layout never changes.
                border = BorderFactory.createEmptyBorder(0, 0, 0, 0)

                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent?) {
                        if (!isHovered) {
                            isHovered = true
                            // Compute the full height needed to display the explanation text.
                            val explanationHeight = calculateExplanationHeight()
                            // The bubble should move down so that its top ends just below the text.
                            targetOffset = explanationHeight + textMargin
                            startAnimation(expanding = true)
                        }
                    }

                    override fun mouseExited(e: MouseEvent?) {
                        if (isHovered) {
                            targetOffset = 0
                            startAnimation(expanding = false)
                        }
                    }
                })
            }

            /**
             * Animates the bubble’s internal offset.
             * When expanding, the bubble moves downward (revealing more text).
             * When collapsing, it moves back upward.
             */
            private fun startAnimation(expanding: Boolean) {
                timer?.stop()
                timer = Timer(animationDelay) {
                    if (expanding) {
                        // Move the bubble downward until it reaches targetOffset
                        if (animationOffset < targetOffset) {
                            animationOffset += animationStep
                            if (animationOffset > targetOffset) animationOffset = targetOffset
                            repaint()
                        } else {
                            timer?.stop()
                        }
                    } else {
                        // Move the bubble upward until animationOffset == 0
                        if (animationOffset > 0) {
                            animationOffset -= animationStep
                            if (animationOffset < 0) animationOffset = 0
                            repaint()
                        } else {
                            // Bubble is fully collapsed; hide text
                            isHovered = false
                            repaint()
                            timer?.stop()
                        }
                    }
                }
                timer?.start()
            }

            /**
             * Computes the height required for the explanation text.
             * Uses word-wrapping based on the bubble width.
             */
            private fun calculateExplanationHeight(): Int {
                val g2 = getGraphics() as? Graphics2D ?: return 40
                g2.font = font.deriveFont(Font.PLAIN, 12f)
                val fm = g2.fontMetrics
                val maxLineWidth = bubbleWidth
                val words = data.explanation.split(" ")
                var currentLine = ""
                var lines = 0
                for (word in words) {
                    val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                    if (fm.stringWidth(testLine) < maxLineWidth - 10) {
                        currentLine = testLine
                    } else {
                        lines++
                        currentLine = word
                    }
                }
                if (currentLine.isNotEmpty()) lines++
                return lines * fm.height + 5
            }

            /**
             * Draws the explanation text using a custom clipping shape.
             * The clipping shape is defined so that its bottom boundary is an arc that spans
             * from one end of the bubble to the other, "molding" the text to the bubble’s top curve.
             */
            private fun drawMoldedText(g2: Graphics2D) {
                // The current bubble position.
                val bubbleX = horizontalPadding
                val bubbleY = bubbleBaseY + animationOffset

                // Create a Path2D that covers from topMargin down to the bubble’s top,
                // then uses the top half of the bubble as the bottom boundary.
                val clipPath = Path2D.Float().apply {
                    // Move to top-left
                    moveTo(bubbleX.toFloat(), topMargin.toFloat())
                    // Go across to top-right
                    lineTo((bubbleX + bubbleWidth).toFloat(), topMargin.toFloat())
                    // Straight down to the bubble's top-right
                    lineTo((bubbleX + bubbleWidth).toFloat(), bubbleY.toFloat())
                    // Append the top half of the bubble (arc) as the bottom boundary
                    // Here, we start at angle=0 (rightmost point) and sweep 180 degrees to the leftmost point
                    val arc = Arc2D.Float(
                        bubbleX.toFloat(),
                        bubbleY.toFloat(),
                        bubbleWidth.toFloat(),
                        bubbleHeight.toFloat(),
                        0f, // start angle
                        180f, // extent
                        Arc2D.OPEN
                    )
                    append(arc, true)
                    closePath()
                }

                // Save the original clipping and set our custom clip.
                val originalClip = g2.clip
                g2.clip = clipPath

                // Draw the explanation text in white.
                g2.font = font.deriveFont(Font.PLAIN, 12f)
                g2.color = Color.WHITE
                val fm = g2.fontMetrics
                val lines = wrapText(g2, data.explanation, bubbleWidth)
                var y = topMargin + fm.ascent
                for (line in lines) {
                    // Explicit cast to String to avoid overload ambiguity.
                    g2.drawString(line as String, bubbleX, y)
                    y += fm.height
                }

                // Restore the original clip.
                g2.clip = originalClip
            }

            /**
             * A simple helper to wrap text to fit within a maximum line width.
             */
            private fun wrapText(g2: Graphics2D, text: String, maxWidth: Int): List<String> {
                val fm = g2.fontMetrics
                val words = text.split(" ")
                val lines = mutableListOf<String>()
                var currentLine = ""
                for (word in words) {
                    val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                    if (fm.stringWidth(testLine) < maxWidth - 10) {
                        currentLine = testLine
                    } else {
                        lines.add(currentLine)
                        currentLine = word
                    }
                }
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                return lines
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                // Draw molded text (if hovered or while animating).
                if (isHovered || animationOffset > 0) {
                    drawMoldedText(g2)
                }

                // Draw the bubble.
                val bubbleX = horizontalPadding
                val bubbleY = bubbleBaseY + animationOffset
                g2.color = Color.LIGHT_GRAY
                g2.fillOval(bubbleX, bubbleY, bubbleWidth, bubbleHeight)
                g2.color = Color.DARK_GRAY
                g2.stroke = BasicStroke(2f)
                g2.drawOval(bubbleX, bubbleY, bubbleWidth, bubbleHeight)

                // Draw the bubble’s name centered within the bubble.
                g2.font = font.deriveFont(Font.BOLD, 14f)
                g2.color = Color.BLACK
                val fm = g2.fontMetrics
                val nameWidth = fm.stringWidth(data.name)
                val nameX = bubbleX + (bubbleWidth - nameWidth) / 2
                val nameY = bubbleY + (bubbleHeight + fm.ascent) / 2 - 4
                g2.drawString(data.name, nameX, nameY)
            }
        }


        inner class CodeTreePanel(private var structure: CodeStructure) : JPanel() {
            init {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                refresh(structure)
            }

            fun refresh(newStructure: CodeStructure) {
                this.structure = newStructure
                removeAll()
                add(buildTreesPanel(structure))
                revalidate()
                repaint()
            }
        }

        inner class BubbleRoadmapPanel(private var bubbleData: List<BubbleData>) : JPanel() {
            private val navigationStack = mutableListOf<List<BubbleData>>()
            private var currentData: List<BubbleData> = bubbleData
            private val bubblesPanel = JPanel().apply {
                layout = FlowLayout(FlowLayout.LEFT, 20, 20)
                background = Color(250, 250, 250)
            }
            private val bubblesScroll = JBScrollPane(bubblesPanel).apply {
                preferredSize = Dimension(400, 300)
            }
            private val backButton = JButton("Back").apply { isVisible = false }

            init {
                layout = BorderLayout()
                add(bubblesScroll, BorderLayout.CENTER)
                add(createBackPanel(), BorderLayout.SOUTH)
                refreshBubbles()
            }

            private fun createBackPanel(): JPanel {
                val backPanel = JPanel(BorderLayout())
                backButton.addActionListener {
                    if (navigationStack.isNotEmpty()) {
                        currentData = navigationStack.removeAt(navigationStack.size - 1)
                        refreshBubbles()
                    }
                }
                backPanel.add(backButton, BorderLayout.WEST)
                return backPanel
            }

            fun refresh() {
                navigationStack.clear()
                currentData = bubbleData
                refreshBubbles()
            }

            fun updateBubbleData(newData: List<BubbleData>) {
                bubbleData = newData
                refresh()
            }

            private fun refreshBubbles() {
                bubblesPanel.removeAll()
                for (bubble in currentData) {
                    val comp = BubbleComponent(bubble)
                    comp.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    comp.addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            if (bubble.children.isNotEmpty()) {
                                navigationStack.add(currentData)
                                animateTransition(bubble.children)
                            }
                        }
                    })
                    bubblesPanel.add(comp)
                }
                bubblesPanel.revalidate()
                bubblesPanel.repaint()
                backButton.isVisible = navigationStack.isNotEmpty()
            }

            private fun animateTransition(newData: List<BubbleData>) {
                Timer(200) {
                    currentData = newData
                    refreshBubbles()
                }.apply {
                    isRepeats = false
                    start()
                }
            }
        }

        fun getMainContent(): JComponent {
            var codeStructure = getElementsTree()
            val codeTreePanel = CodeTreePanel(codeStructure)
            var bubbleDataList = buildBubbleData(codeStructure)
            val bubbleRoadmapPanel = BubbleRoadmapPanel(bubbleDataList)

            val tabbedPane = JTabbedPane()
            tabbedPane.addTab("Code Tree", codeTreePanel)
            tabbedPane.addTab("Bubble Roadmap", bubbleRoadmapPanel)

            val refreshButton = JButton("Refresh").apply {
                toolTipText = "Refresh both views"
                addActionListener {
                    codeStructure = getElementsTree()
                    codeTreePanel.refresh(codeStructure)
                    bubbleDataList = buildBubbleData(codeStructure)
                    bubbleRoadmapPanel.updateBubbleData(bubbleDataList)
                }
            }

            val topPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(refreshButton)
            }

            return JPanel(BorderLayout()).apply {
                add(topPanel, BorderLayout.NORTH)
                add(tabbedPane, BorderLayout.CENTER)
            }
        }

        private fun buildTreesPanel(codeStructure: CodeStructure): JPanel {
            val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
            val structureRoot = DefaultMutableTreeNode("Code Structure")
            val executor = Executors.newFixedThreadPool(4)
            val futures = mutableMapOf<String, Future<String>>()

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

            codeStructure.topLevelClasses.forEach { collectClassAndMethods(it) }
            codeStructure.topLevelMethods.forEach { method ->
                val methodText = ReadAction.compute<String, Throwable> { method.text }
                futures[methodText] = executor.submit(Callable { getOpenAISummary(methodText) })
            }

            executor.shutdown()
            executor.awaitTermination(60, TimeUnit.SECONDS)

            fun buildClassNode(cls: PsiClass): DefaultMutableTreeNode {
                val className = ReadAction.compute<String, Throwable> { cls.name ?: "UnnamedClass" }
                val structureNode = DefaultMutableTreeNode(NodeData("\uD83C\uDDE8 $className", cls))
                codeStructure.classMethods[cls]?.forEach { method ->
                    val methodName = ReadAction.compute<String, Throwable> { method.name ?: "Unnamed" }
                    structureNode.add(DefaultMutableTreeNode(NodeData("\uD83C\uDDF2 $methodName()", method)))
                }
                codeStructure.classChildren[cls]?.forEach { childCls ->
                    val childNode = buildClassNode(childCls)
                    structureNode.add(childNode)
                }
                return structureNode
            }

            codeStructure.topLevelClasses.forEach { cls ->
                val node = buildClassNode(cls)
                structureRoot.add(node)
            }
            codeStructure.topLevelMethods.forEach { method ->
                val methodName = ReadAction.compute<String, Throwable> { method.name ?: "Unnamed" }
                structureRoot.add(DefaultMutableTreeNode(NodeData("🔹 $methodName()", method)))
            }

            val structureTree = JTree(DefaultTreeModel(structureRoot)).apply {
                isRootVisible = true
                addTreeSelectionListener {
                    val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
                    val data = node.userObject as? NodeData ?: return@addTreeSelectionListener
                    navigateToElement(data.element)
                }
                expandRow(0)
            }

            panel.add(JBScrollPane(structureTree).apply { preferredSize = Dimension(300, 600) })
            return panel
        }

        private fun buildBubbleData(codeStructure: CodeStructure): List<BubbleData> {
            val executor = Executors.newFixedThreadPool(4)
            val futures = mutableMapOf<String, Future<String>>()

            fun collectExplanationsForClass(cls: PsiClass) {
                val classText = ReadAction.compute<String, Throwable> { cls.text }
                futures[classText] = executor.submit(Callable { getOpenAISummary(classText) })
                codeStructure.classMethods[cls]?.forEach { method ->
                    val methodText = ReadAction.compute<String, Throwable> { method.text }
                    futures[methodText] = executor.submit(Callable { getOpenAISummary(methodText) })
                }
                codeStructure.classChildren[cls]?.forEach { childCls ->
                    collectExplanationsForClass(childCls)
                }
            }

            codeStructure.topLevelClasses.forEach { collectExplanationsForClass(it) }
            codeStructure.topLevelMethods.forEach { method ->
                val methodText = ReadAction.compute<String, Throwable> { method.text }
                futures[methodText] = executor.submit(Callable { getOpenAISummary(methodText) })
            }

            executor.shutdown()
            executor.awaitTermination(60, TimeUnit.SECONDS)

            fun buildClassBubble(cls: PsiClass): BubbleData {
                val name = ReadAction.compute<String, Throwable> { cls.name ?: "UnnamedClass" }
                val classText = ReadAction.compute<String, Throwable> { cls.text }
                val explanation = futures[classText]?.get() ?: "No explanation"
                val childBubbles = mutableListOf<BubbleData>()
                codeStructure.classMethods[cls]?.forEach { method ->
                    childBubbles.add(buildMethodBubble(method, futures))
                }
                codeStructure.classChildren[cls]?.forEach { childCls ->
                    childBubbles.add(buildClassBubble(childCls))
                }
                return BubbleData(name, explanation, childBubbles)
            }

            val bubbleList = mutableListOf<BubbleData>()
            codeStructure.topLevelClasses.forEach { cls ->
                bubbleList.add(buildClassBubble(cls))
            }
            codeStructure.topLevelMethods.forEach { method ->
                bubbleList.add(buildMethodBubble(method, futures))
            }

            return bubbleList
        }

        private fun buildMethodBubble(method: PsiMethod, futures: Map<String, Future<String>>): BubbleData {
            val name = ReadAction.compute<String, Throwable> { method.name ?: "Unnamed" }
            val methodText = ReadAction.compute<String, Throwable> { method.text }
            val explanation = futures[methodText]?.get() ?: "No explanation"
            return BubbleData(name, explanation)
        }

        private fun getElementsTree(): CodeStructure {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
                ?: return CodeStructure(emptyList(), emptyList(), emptyMap(), emptyMap())
            val document = editor.document
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
                ?: return CodeStructure(emptyList(), emptyList(), emptyMap(), emptyMap())
            val allClasses = PsiTreeUtil.findChildrenOfType(psiFile, PsiClass::class.java)
            val allMethods = PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java)
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
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }

        private fun getOpenAISummary(code: String): String {
            val apiKey = loadApiKey() ?: return "❌ No API key set"
            return try {
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
                "❌ API Error: ${e.message}"
            }
        }
    }
}
