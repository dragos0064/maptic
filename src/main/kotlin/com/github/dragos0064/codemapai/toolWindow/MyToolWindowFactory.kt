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
import java.awt.event.*
import java.awt.geom.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.concurrent.*
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import kotlin.math.max

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
            val envFile = File("C:\\Users\\drago\\codemapai\\.env")
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

        // ---------------------------------------------------
        // New Diagram Types & BFS-based Class Diagram Panel
        // ---------------------------------------------------

        /**
         * Represents a node in the class diagram.
         */
        data class DiagramNode(
            val name: String,
            val children: MutableList<DiagramNode> = mutableListOf()
        ) {
            var x: Int = 0
            var y: Int = 0
        }

        /**
         * Converts your CodeStructure into DiagramNodes.
         * For each class, creates a node with its name and adds its methods (prefixed with 🔹) as children.
         * Also adds top-level methods as separate nodes.
         */
        private fun buildDiagramNodes(structure: CodeStructure): MutableList<DiagramNode> {
            val nodeMap = mutableMapOf<PsiClass, DiagramNode>()
            // Include all classes from top-level and nested ones.
            val allClasses = structure.topLevelClasses + structure.classChildren.values.flatten()
            for (cls in allClasses) {
                val className = ReadAction.compute<String, Throwable> { cls.name ?: "UnnamedClass" }
                val classNode = DiagramNode(className)
                // Add methods as children.
                val methods = structure.classMethods[cls] ?: emptyList()
                for (method in methods) {
                    val methodName = ReadAction.compute<String, Throwable> { method.name ?: "Unnamed" }
                    classNode.children.add(DiagramNode("🔹 $methodName()"))
                }
                nodeMap[cls] = classNode
            }
            // Build parent-child relationships between classes.
            for ((parent, children) in structure.classChildren) {
                val parentNode = nodeMap[parent]
                if (parentNode != null) {
                    for (child in children) {
                        val childNode = nodeMap[child]
                        if (childNode != null) {
                            parentNode.children.add(childNode)
                        }
                    }
                }
            }
            // Create nodes for top-level methods.
            val topLevelMethodNodes = structure.topLevelMethods.map { method ->
                val methodName = ReadAction.compute<String, Throwable> { method.name ?: "Unnamed" }
                DiagramNode("🔹 $methodName()")
            }
            // Return roots: nodes that are top-level classes plus top-level methods.
            return (structure.topLevelClasses.mapNotNull { nodeMap[it] } + topLevelMethodNodes).toMutableList()
        }

        /**
         * A BFS-based diagram panel that lays out nodes level-by-level.
         * This version uses very small nodes so that the entire diagram fits.
         */
        class BFSClassDiagramPanel(val topLevelNodes: MutableList<DiagramNode>) : JPanel() {

            // Very small node dimensions for compactness.
            private val nodeWidth = 80
            private val nodeHeight = 40

            // Smaller gaps.
            private val levelGap = 30     // Vertical gap between rows
            private val nodeGap = 10       // Horizontal gap between nodes
            private val topMargin = 20     // Top margin
            private val leftMargin = 20    // Left margin

            // Edge styling
            private val edgeColor = Color.GRAY
            private val edgeStroke = BasicStroke(1f)

            init {
                layout = null
                addComponentListener(object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent) {
                        doLayoutWithBFS()
                        repaint()
                    }
                })
                doLayoutWithBFS()
            }

            /**
             * Performs a BFS to assign each node a level and then groups nodes by level
             * to position them in horizontal rows.
             */
            fun doLayoutWithBFS() {
                val nodeLevel = mutableMapOf<DiagramNode, Int>()
                val queue = ArrayDeque<DiagramNode>()
                for (root in topLevelNodes) {
                    nodeLevel[root] = 0
                    queue.add(root)
                }
                while (queue.isNotEmpty()) {
                    val current = queue.removeFirst()
                    val level = nodeLevel[current] ?: 0
                    for (child in current.children) {
                        if (child !in nodeLevel) {
                            nodeLevel[child] = level + 1
                            queue.add(child)
                        }
                    }
                }
                val levelToNodes = nodeLevel.entries.groupBy({ it.value }, { it.key })
                val panelWidth = width.coerceAtLeast(400)
                for ((level, nodes) in levelToNodes) {
                    val sortedNodes = nodes.sortedBy { it.name }
                    val rowWidth = sortedNodes.size * nodeWidth + (sortedNodes.size - 1) * nodeGap
                    var startX = (panelWidth - rowWidth) / 2 + leftMargin
                    val y = topMargin + level * (nodeHeight + levelGap)
                    for (node in sortedNodes) {
                        node.x = startX
                        node.y = y
                        startX += nodeWidth + nodeGap
                    }
                }
                val totalLevels = (levelToNodes.keys.maxOrNull() ?: 0) + 1
                val neededHeight = topMargin + totalLevels * (nodeHeight + levelGap)
                preferredSize = Dimension(panelWidth, neededHeight)
                revalidate()
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                for (root in topLevelNodes) {
                    drawEdgesRecursively(g2, root)
                }
                for (root in topLevelNodes) {
                    drawNodesRecursively(g2, root)
                }
            }

            private fun drawEdgesRecursively(g2: Graphics2D, parent: DiagramNode) {
                for (child in parent.children) {
                    val parentCenterX = parent.x + nodeWidth / 2
                    val parentCenterY = parent.y + nodeHeight
                    val childCenterX = child.x + nodeWidth / 2
                    val childCenterY = child.y
                    g2.color = edgeColor
                    g2.stroke = edgeStroke
                    g2.drawLine(parentCenterX, parentCenterY, childCenterX, childCenterY)
                    drawEdgesRecursively(g2, child)
                }
            }

            private fun drawNodesRecursively(g2: Graphics2D, node: DiagramNode) {
                val bubble = Ellipse2D.Float(
                    node.x.toFloat(),
                    node.y.toFloat(),
                    nodeWidth.toFloat(),
                    nodeHeight.toFloat()
                )
                g2.color = Color(240, 240, 240)
                g2.fill(bubble)
                g2.color = Color.DARK_GRAY
                g2.stroke = BasicStroke(1f)
                g2.draw(bubble)
                g2.font = g2.font.deriveFont(Font.BOLD, 10f)
                g2.color = Color.BLACK
                val fm = g2.fontMetrics
                val textWidth = fm.stringWidth(node.name)
                val textX = node.x + (nodeWidth - textWidth) / 2
                val textY = node.y + (nodeHeight + fm.ascent) / 2 - 2
                g2.drawString(node.name, textX, textY)
                for (child in node.children) {
                    drawNodesRecursively(g2, child)
                }
            }
        }

        // ---------------------------------------------------
        // Existing Components (BubbleComponent, CodeTreePanel, BubbleRoadmapPanel)
        // ---------------------------------------------------

        class BubbleComponent(val data: BubbleData) : JPanel() {
            private val componentWidth = 250
            private val componentHeight = 300
            private val bubbleWidth = 200
            private val bubbleHeight = 80
            private val topMargin = 10
            private val bubbleBaseY = topMargin
            private var animationOffset = 0
            private var targetOffset = 0
            private var timer: Timer? = null
            private var isHovered = false
            private val animationStep = 6
            private val animationDelay = 10
            private val textMargin = 5
            private val horizontalPadding = 10

            init {
                preferredSize = Dimension(componentWidth, componentHeight)
                isOpaque = false
                border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent?) {
                        if (!isHovered) {
                            isHovered = true
                            val explanationHeight = calculateExplanationHeight()
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

            private fun startAnimation(expanding: Boolean) {
                timer?.stop()
                timer = Timer(animationDelay) {
                    if (expanding) {
                        if (animationOffset < targetOffset) {
                            animationOffset += animationStep
                            if (animationOffset > targetOffset) animationOffset = targetOffset
                            repaint()
                        } else {
                            timer?.stop()
                        }
                    } else {
                        if (animationOffset > 0) {
                            animationOffset -= animationStep
                            if (animationOffset < 0) animationOffset = 0
                            repaint()
                        } else {
                            isHovered = false
                            repaint()
                            timer?.stop()
                        }
                    }
                }
                timer?.start()
            }

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

            private fun drawMoldedText(g2: Graphics2D) {
                val bubbleX = horizontalPadding
                val bubbleY = bubbleBaseY + animationOffset
                val clipPath = Path2D.Float().apply {
                    moveTo(bubbleX.toFloat(), topMargin.toFloat())
                    lineTo((bubbleX + bubbleWidth).toFloat(), topMargin.toFloat())
                    lineTo((bubbleX + bubbleWidth).toFloat(), bubbleY.toFloat())
                    val arc = Arc2D.Float(
                        bubbleX.toFloat(),
                        bubbleY.toFloat(),
                        bubbleWidth.toFloat(),
                        bubbleHeight.toFloat(),
                        0f,
                        180f,
                        Arc2D.OPEN
                    )
                    append(arc, true)
                    closePath()
                }
                val originalClip = g2.clip
                g2.clip = clipPath
                g2.font = font.deriveFont(Font.PLAIN, 12f)
                g2.color = Color.WHITE
                val fm = g2.fontMetrics
                val lines = wrapText(g2, data.explanation, bubbleWidth)
                var y = topMargin + fm.ascent
                for (line in lines) {
                    g2.drawString(line as String, bubbleX, y)
                    y += fm.height
                }
                g2.clip = originalClip
            }

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
                if (isHovered || animationOffset > 0) {
                    drawMoldedText(g2)
                }
                val bubbleX = horizontalPadding
                val bubbleY = bubbleBaseY + animationOffset
                g2.color = Color.LIGHT_GRAY
                g2.fillOval(bubbleX, bubbleY, bubbleWidth, bubbleHeight)
                g2.color = Color.DARK_GRAY
                g2.stroke = BasicStroke(2f)
                g2.drawOval(bubbleX, bubbleY, bubbleWidth, bubbleHeight)
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
            // Build diagram nodes (including methods) and create the BFS diagram panel.
            val diagramNodes = buildDiagramNodes(codeStructure)
            // Use all top-level classes as roots so that every parent class appears.
            val classDiagramPanel = BFSClassDiagramPanel(diagramNodes)

            val tabbedPane = JTabbedPane()
            tabbedPane.addTab("Code Tree", codeTreePanel)
            tabbedPane.addTab("Bubble Roadmap", bubbleRoadmapPanel)
            tabbedPane.addTab("Class Diagram", JBScrollPane(classDiagramPanel))

            val refreshButton = JButton("Refresh").apply {
                toolTipText = "Refresh all views"
                addActionListener {
                    codeStructure = getElementsTree()
                    codeTreePanel.refresh(codeStructure)
                    bubbleDataList = buildBubbleData(codeStructure)
                    bubbleRoadmapPanel.updateBubbleData(bubbleDataList)
                    val newDiagramNodes = buildDiagramNodes(codeStructure)
                    diagramNodes.clear()
                    diagramNodes.addAll(newDiagramNodes)
                    classDiagramPanel.doLayoutWithBFS()
                    classDiagramPanel.revalidate()
                    classDiagramPanel.repaint()
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
