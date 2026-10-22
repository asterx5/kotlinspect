@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)

package io.github.asterx5.kotlinspect.internal

import io.github.asterx5.kotlinspect.CallState
import io.github.asterx5.kotlinspect.internal.db.LogEntity
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.github.asterx5.kotlinspect.internal.db.RecordSummary
import io.github.asterx5.kotlinspect.internal.db.SessionEntity
import io.github.asterx5.kotlinspect.internal.uimodel.KsPalette
import io.github.asterx5.kotlinspect.internal.uimodel.NO_LAYER
import io.github.asterx5.kotlinspect.internal.uimodel.StatusFilter
import io.github.asterx5.kotlinspect.internal.uimodel.filterLogs
import io.github.asterx5.kotlinspect.internal.uimodel.levelLetter
import io.github.asterx5.kotlinspect.internal.uimodel.presentLevels
import io.github.asterx5.kotlinspect.internal.uimodel.presentValues
import io.github.asterx5.kotlinspect.internal.uimodel.filterRecords
import io.github.asterx5.kotlinspect.internal.uimodel.jsonTokens
import io.github.asterx5.kotlinspect.internal.uimodel.looksLikeJsonLines
import io.github.asterx5.kotlinspect.internal.uimodel.orderMethods
import io.github.asterx5.kotlinspect.internal.uimodel.toCurl
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSAttributedString
import platform.Foundation.NSIndexPath
import platform.Foundation.NSMutableAttributedString
import platform.Foundation.appendAttributedString
import platform.Foundation.create
import platform.UIKit.NSFontAttributeName
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.UIAction
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIBarButtonSystemItem
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIDevice
import platform.UIKit.UIFont
import platform.UIKit.UIFontWeightBold
import platform.UIKit.UIFontWeightMedium
import platform.UIKit.UIFontWeightRegular
import platform.UIKit.UIFontWeightSemibold
import platform.UIKit.UIImage
import platform.UIKit.UIImageRenderingMode
import platform.UIKit.UILabel
import platform.UIKit.UILayoutConstraintAxisHorizontal
import platform.UIKit.UIMenu
import platform.UIKit.UIMenuElementAttributesDestructive
import platform.UIKit.UIMenuElementState
import platform.UIKit.UINavigationController
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIPasteboard
import platform.UIKit.UIScreen
import platform.UIKit.UISearchController
import platform.UIKit.UISearchResultsUpdatingProtocol
import platform.UIKit.UISegmentedControl
import platform.UIKit.UIStackView
import platform.UIKit.UITableView
import platform.UIKit.UITableViewCell
import platform.UIKit.UITableViewCellAccessoryType
import platform.UIKit.UITableViewCellStyle
import platform.UIKit.UITableViewDataSourceProtocol
import platform.UIKit.UITableViewDelegateProtocol
import platform.UIKit.UITableViewStyle
import platform.UIKit.UITextView
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.labelColor
import platform.UIKit.navigationItem
import platform.UIKit.row
import platform.UIKit.secondaryLabelColor
import platform.UIKit.systemBackgroundColor
import platform.UIKit.tertiaryLabelColor
import platform.darwin.NSInteger
import platform.darwin.NSObject

/**
 * The inspector built from UIKit: a navigation controller with Network and Logs tabs, each a
 * searchable table with a detail screen. It reads the same database as the Compose inspector, so behavior is identical.
 */
internal class NativeInspector(
    private val runtime: KotlinspectRuntime,
    initialSessionId: String?,
    private val onClose: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val colors: KsPalette = KsPalette.of(
        dark = UIScreen.mainScreen.traitCollection.userInterfaceStyle == UIUserInterfaceStyle.UIUserInterfaceStyleDark,
    )
    private val startSession: String? = initialSessionId

    // Strong references: UIKit holds data sources and delegates weakly.
    private val calls = CallsScreen()
    private val navigationDelegate = NavigationDelegate()
    private var detailJob: Job? = null

    val root: UIViewController = UINavigationController(rootViewController = calls.controller).apply {
        view.tintColor = colors.accent.ui()
        delegate = navigationDelegate
    }

    fun dispose() {
        scope.cancel()
    }

    // region Calls

    private inner class CallsScreen {
        val controller = UIViewController(nibName = null, bundle = null)
        private val table = UITableView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), style = UITableViewStyle.UITableViewStylePlain)
        private val tabs = UISegmentedControl(items = listOf("Network", "Logs"))
        private val filter = UISegmentedControl(items = StatusFilter.entries.map { it.label })
        private val methodFilter = UISegmentedControl(items = listOf("Any"))
        private var methods: List<String> = emptyList()
        private val stats = UILabel()
        private val empty = UILabel()
        private val search = UISearchController(searchResultsController = null)
        private val sessionsItem = UIBarButtonItem(image = UIImage.systemImageNamed("rectangle.stack"), menu = null)
        private val networkHeader = UIView(frame = CGRectMake(0.0, 0.0, UIScreen.mainScreen.bounds.width(), 116.0))

        // Logs tab: multi-select filters as menus, listing only values present in the session.
        private val logHeader = UIView(frame = CGRectMake(0.0, 0.0, UIScreen.mainScreen.bounds.width(), 78.0))
        private val levelButton = filterButton()
        private val tagButton = filterButton()
        private val layerButton = filterButton()
        private val filterButtons = UIStackView(arrangedSubviews = listOf(levelButton, tagButton, layerButton))
        private val logStats = UILabel()

        private val viewedSession = MutableStateFlow<String?>(startSession)
        private var all: List<RecordSummary> = emptyList()
        private var shown: List<RecordSummary> = emptyList()
        private var query = ""

        private var allLogs: List<LogEntity> = emptyList()
        private var shownLogs: List<LogEntity> = emptyList()
        private var logQuery = ""
        private var logTags: Set<String> = emptySet()
        private var logLayers: Set<String> = emptySet()
        private var logLevels: Set<String> = emptySet()

        private val logsTab: Boolean get() = tabs.selectedSegmentIndex == 1L

        private val dataSource = DataSource()
        private val tableDelegate = TableDelegate()
        private val searchUpdater = SearchUpdater()

        init {
            controller.view.backgroundColor = UIColor.systemBackgroundColor
            controller.view.addSubview(table)
            table.setFrame(controller.view.bounds)
            table.autoresizingMask = FLEXIBLE_SIZE
            table.dataSource = dataSource
            table.delegate = tableDelegate
            table.rowHeight = 64.0
            table.estimatedRowHeight = 64.0
            table.backgroundView = empty

            empty.text = "Waiting for traffic"
            empty.textAlignment = platform.UIKit.NSTextAlignmentCenter
            empty.textColor = UIColor.secondaryLabelColor
            empty.numberOfLines = 0

            // Network header: status filter, method filter and stats line.
            filter.selectedSegmentIndex = 0
            filter.setFrame(CGRectMake(16.0, 8.0, UIScreen.mainScreen.bounds.width() - 32.0, 32.0))
            filter.autoresizingMask = platform.UIKit.UIViewAutoresizingFlexibleWidth
            filter.addAction(UIAction.actionWithHandler { _ -> refresh() }, forControlEvents = UIControlEventValueChanged)
            methodFilter.selectedSegmentIndex = 0
            methodFilter.setFrame(CGRectMake(16.0, 46.0, UIScreen.mainScreen.bounds.width() - 32.0, 32.0))
            methodFilter.autoresizingMask = platform.UIKit.UIViewAutoresizingFlexibleWidth
            methodFilter.addAction(UIAction.actionWithHandler { _ -> refresh() }, forControlEvents = UIControlEventValueChanged)
            networkHeader.addSubview(methodFilter)
            stats.setFrame(CGRectMake(16.0, 86.0, UIScreen.mainScreen.bounds.width() - 32.0, 22.0))
            stats.autoresizingMask = platform.UIKit.UIViewAutoresizingFlexibleWidth
            stats.font = UIFont.monospacedSystemFontOfSize(12.0, UIFontWeightMedium)
            stats.textColor = UIColor.secondaryLabelColor
            networkHeader.addSubview(filter)
            networkHeader.addSubview(stats)
            table.tableHeaderView = networkHeader

            // Logs header: level, tag and layer menus, then the stats line.
            filterButtons.axis = UILayoutConstraintAxisHorizontal
            filterButtons.spacing = 8.0
            filterButtons.alignment = platform.UIKit.UIStackViewAlignmentCenter
            filterButtons.setFrame(CGRectMake(16.0, 8.0, UIScreen.mainScreen.bounds.width() - 32.0, 32.0))
            logHeader.addSubview(filterButtons)
            logStats.autoresizingMask = platform.UIKit.UIViewAutoresizingFlexibleWidth
            logStats.font = UIFont.monospacedSystemFontOfSize(12.0, UIFontWeightMedium)
            logStats.textColor = UIColor.secondaryLabelColor
            logHeader.addSubview(logStats)

            tabs.selectedSegmentIndex = 0
            tabs.addAction(UIAction.actionWithHandler { _ -> switchTab() }, forControlEvents = UIControlEventValueChanged)
            controller.navigationItem.titleView = tabs

            search.searchResultsUpdater = searchUpdater
            search.obscuresBackgroundDuringPresentation = false
            search.searchBar.placeholder = "Filter by URL, method or status"

            controller.navigationItem.searchController = search
            controller.navigationItem.hidesSearchBarWhenScrolling = false
            controller.navigationItem.leftBarButtonItem = UIBarButtonItem(
                barButtonSystemItem = UIBarButtonSystemItem.UIBarButtonSystemItemClose,
                primaryAction = UIAction.actionWithHandler { _ -> onClose() },
            )
            controller.navigationItem.rightBarButtonItem = sessionsItem

            observe()
        }

        private fun switchTab() {
            search.searchBar.text = if (logsTab) logQuery else query
            search.searchBar.placeholder = if (logsTab) "Search messages" else "Filter by URL, method or status"
            table.tableHeaderView = if (logsTab) logHeader else networkHeader
            table.rowHeight = if (logsTab) platform.UIKit.UITableViewAutomaticDimension else 64.0
            refresh()
            table.setContentOffset(CGPointMake(0.0, 0.0), animated = false)
        }

        private fun observe() {
            scope.launch {
                combine(runtime.database.sessions().observeAll(), runtime.currentSessionId, viewedSession) { sessions, current, viewed ->
                    Triple(sessions, current, viewed)
                }.collect { (sessions, current, viewed) ->
                    val id = viewed?.takeIf { v -> sessions.any { it.id == v } } ?: current
                    val name = sessions.firstOrNull { it.id == id }?.name ?: "Kotlinspect"
                    controller.navigationItem.title = name
                    // The title area holds the Network/Logs switch, so the session name goes in the prompt.
                    controller.navigationItem.prompt = if (id == current) "● Recording · $name" else "Past session · $name"
                    sessionsItem.setMenu(sessionsMenu(sessions, id, current))
                }
            }
            val viewedId = combine(runtime.currentSessionId, viewedSession) { current, viewed -> viewed ?: current }
            scope.launch {
                viewedId
                    .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else runtime.database.records().observeSummaries(id) }
                    .collect { list ->
                        all = list
                        refresh()
                    }
            }
            scope.launch {
                viewedId
                    .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else runtime.database.logs().observeSession(id) }
                    .collect { list ->
                        allLogs = list
                        refresh()
                    }
            }
        }

        private fun sessionsMenu(sessions: List<SessionEntity>, viewedId: String?, currentId: String?): UIMenu {
            val sessionActions = sessions.map { s ->
                UIAction.actionWithTitle(
                    title = (if (s.id == currentId) "● " else "") + s.name,
                    image = null,
                    identifier = null,
                    handler = { _ -> viewedSession.value = s.id },
                ).apply { if (s.id == viewedId) state = UIMenuElementState.UIMenuElementStateOn }
            }
            val manage = buildList {
                add(action("New session", "plus") { viewedSession.value = runtime.startSession(null, emptySet(), emptyMap(), null) })
                if (viewedId != null && viewedId != currentId) add(action("Record here", "record.circle") { runtime.switchSession(viewedId) })
                if (viewedId != null) {
                    add(action("Clear calls and logs", "eraser") { runtime.clearSession(viewedId) })
                    add(action("Delete session", "trash", destructive = true) {
                        runtime.deleteSession(viewedId)
                        viewedSession.value = null
                    })
                }
                add(action("Clear everything", "trash.slash", destructive = true) {
                    runtime.clearAll()
                    viewedSession.value = null
                })
            }
            return UIMenu.menuWithTitle(
                title = "",
                children = listOf(
                    UIMenu.menuWithTitle(title = "Sessions", image = null, identifier = null, options = DISPLAY_INLINE, children = sessionActions),
                    UIMenu.menuWithTitle(title = "", image = null, identifier = null, options = DISPLAY_INLINE, children = manage),
                ),
            )
        }

        fun refresh() {
            tabs.setTitle("Network · ${all.size}", forSegmentAtIndex = 0u)
            tabs.setTitle("Logs · ${allLogs.size}", forSegmentAtIndex = 1u)
            tabs.sizeToFit()
            if (logsTab) refreshLogs() else refreshCalls()
            table.reloadData()
        }

        private fun refreshCalls() {
            val status = StatusFilter.entries.getOrElse(filter.selectedSegmentIndex.toInt()) { StatusFilter.All }
            updateMethodSegments()
            val method = methods.getOrNull(methodFilter.selectedSegmentIndex.toInt() - 1)
            shown = filterRecords(all, query, status, method)
            val errors = all.count { it.callState == CallState.Failed || (it.statusCode ?: 0) >= 400 }
            val done = all.mapNotNull { it.durationMs }
            stats.text = "${all.size} calls  ·  $errors errors  ·  ${formatBytes(all.sumOf { it.responseBodySize ?: 0 })}" +
                if (done.isEmpty()) "" else "  ·  avg ${formatDuration(done.sum() / done.size)}"
            empty.text = if (all.isEmpty()) "Waiting for traffic" else "Nothing matches"
            empty.hidden = shown.isNotEmpty()
        }

        private fun refreshLogs() {
            val levels = presentLevels(allLogs)
            val tags = presentValues(allLogs) { it.tag }
            val layers = presentValues(allLogs) { it.layer ?: NO_LAYER }
            // Drop selections that no longer exist, e.g. after clearing the session.
            logLevels = logLevels.intersect(levels.map { it.first }.toSet())
            logTags = logTags.intersect(tags.map { it.first }.toSet())
            logLayers = logLayers.intersect(layers.map { it.first }.toSet())

            configure(levelButton, "Level", levels, logLevels, label = { "${levelLetter(it)}  $it" }, color = { colors.levelColor(it) }) { logLevels = it }
            configure(tagButton, "Tags", tags, logTags) { logTags = it }
            configure(layerButton, "Layers", layers, logLayers) { logLayers = it }

            // The header shrinks to the stats line when no filter has anything to choose between.
            val anyFilter = !levelButton.hidden || !tagButton.hidden || !layerButton.hidden
            filterButtons.hidden = !anyFilter
            val statsY = if (anyFilter) 48.0 else 8.0
            val width = UIScreen.mainScreen.bounds.width()
            logStats.setFrame(CGRectMake(16.0, statsY, width - 32.0, 22.0))
            val height = statsY + 30.0
            if (logHeader.frame.useContents { size.height } != height) {
                logHeader.setFrame(CGRectMake(0.0, 0.0, width, height))
                if (logsTab) table.tableHeaderView = logHeader
            }

            shownLogs = filterLogs(allLogs, logQuery, logTags, logLayers, logLevels)
            val problems = allLogs.count { it.level == "Warn" || it.level == "Error" || it.level == "Assert" }
            logStats.text = "${allLogs.size} logs  ·  $problems warnings or errors" +
                if (shownLogs.size != allLogs.size) "  ·  ${shownLogs.size} shown" else ""
            empty.text = if (allLogs.isEmpty()) "No logs yet\nEntries logged with KtLog appear here." else "Nothing matches"
            empty.hidden = shownLogs.isNotEmpty()
        }

        /** Sets up one multi-select filter menu, or hides it when there is nothing to choose between. */
        private fun configure(
            button: UIButton,
            title: String,
            values: List<Pair<String, Int>>,
            selected: Set<String>,
            label: (String) -> String = { it },
            color: ((String) -> Long)? = null,
            onChange: (Set<String>) -> Unit,
        ) {
            button.hidden = values.size <= 1
            // Spaces pad the title inside the pill; content insets are not available here before iOS 15.
            button.setTitle(if (selected.isEmpty()) "   $title ▾   " else "   $title · ${selected.size} ▾   ", forState = UIControlStateNormal)
            button.backgroundColor = if (selected.isEmpty()) colors.surfaceAlt.ui() else colors.accent.ui().colorWithAlphaComponent(0.18)
            val options = values.map { (value, count) ->
                UIAction.actionWithTitle(
                    title = "${label(value)}   $count",
                    image = color?.let { c ->
                        UIImage.systemImageNamed("circle.fill")?.imageWithTintColor(c(value).ui(), renderingMode = UIImageRenderingMode.UIImageRenderingModeAlwaysOriginal)
                    },
                    identifier = null,
                    handler = { _ ->
                        onChange(if (value in selected) selected - value else selected + value)
                        refresh()
                    },
                ).apply { if (value in selected) state = UIMenuElementState.UIMenuElementStateOn }
            }
            val clear = if (selected.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    action("Clear", "xmark") {
                        onChange(emptySet())
                        refresh()
                    },
                )
            }
            button.menu = UIMenu.menuWithTitle(
                title = "",
                children = listOf(
                    UIMenu.menuWithTitle(title = title, image = null, identifier = null, options = DISPLAY_INLINE, children = options),
                    UIMenu.menuWithTitle(title = "", image = null, identifier = null, options = DISPLAY_INLINE, children = clear),
                ),
            )
        }

        /** Rebuilds the method segments when the set of methods in the session changes. */
        private fun updateMethodSegments() {
            val present = orderMethods(all.map { it.method })
            if (present == methods) return
            val selected = methods.getOrNull(methodFilter.selectedSegmentIndex.toInt() - 1)
            methods = present
            methodFilter.removeAllSegments()
            (listOf("Any") + present).forEachIndexed { i, title ->
                methodFilter.insertSegmentWithTitle(title, atIndex = i.toULong(), animated = false)
            }
            val index = selected?.let { present.indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: 0
            methodFilter.selectedSegmentIndex = index.toLong()
            methodFilter.hidden = present.isEmpty()
        }

        private inner class DataSource : NSObject(), UITableViewDataSourceProtocol {
            override fun tableView(tableView: UITableView, numberOfRowsInSection: NSInteger): NSInteger =
                (if (logsTab) shownLogs.size else shown.size).toLong()

            override fun tableView(tableView: UITableView, cellForRowAtIndexPath: NSIndexPath): UITableViewCell {
                val row = cellForRowAtIndexPath.row.toInt()
                return if (logsTab) logCell(tableView, shownLogs[row]) else callCell(tableView, shown[row])
            }
        }

        private fun callCell(tableView: UITableView, r: RecordSummary): UITableViewCell {
            val cell = tableView.dequeueReusableCellWithIdentifier(CELL)
                ?: UITableViewCell(style = UITableViewCellStyle.UITableViewCellStyleSubtitle, reuseIdentifier = CELL)
            cell.textLabel?.attributedText = attributed {
                text("${r.method.padEnd(6)} ", mono(13.0, UIFontWeightBold), colors.methodColor(r.method).ui())
                text(r.path, mono(13.0, UIFontWeightRegular), UIColor.labelColor)
            }
            cell.textLabel?.lineBreakMode = platform.UIKit.NSLineBreakByTruncatingMiddle
            cell.detailTextLabel?.text = listOfNotNull(
                r.host,
                formatTime(r.startedAt),
                formatDuration(r.durationMs),
                r.responseBodySize?.let(::formatBytes),
            ).joinToString("  ·  ")
            cell.detailTextLabel?.textColor = UIColor.secondaryLabelColor
            cell.detailTextLabel?.font = UIFont.systemFontOfSize(12.0)
            val status = (cell.accessoryView as? UILabel) ?: UILabel().also { cell.accessoryView = it }
            status.text = statusLabel(r)
            status.font = mono(15.0, UIFontWeightBold)
            status.textColor = colors.statusColor(r.callState, r.statusCode).ui()
            status.sizeToFit()
            return cell
        }

        /** Level letter, tag and layer on top; up to three lines of the message below. */
        private fun logCell(tableView: UITableView, log: LogEntity): UITableViewCell {
            val cell = tableView.dequeueReusableCellWithIdentifier(LOG_CELL)
                ?: UITableViewCell(style = UITableViewCellStyle.UITableViewCellStyleSubtitle, reuseIdentifier = LOG_CELL)
            cell.textLabel?.attributedText = attributed {
                text(levelLetter(log.level), mono(13.0, UIFontWeightBold), colors.levelColor(log.level).ui())
                text("  ${log.tag}", UIFont.systemFontOfSize(14.0, UIFontWeightSemibold), UIColor.labelColor)
                log.layer?.let { text("  ·  $it", caption(), UIColor.secondaryLabelColor) }
            }
            cell.textLabel?.lineBreakMode = platform.UIKit.NSLineBreakByTruncatingTail
            cell.detailTextLabel?.numberOfLines = 3
            cell.detailTextLabel?.attributedText = attributed {
                text(log.message.take(PREVIEW_CHARS), mono(12.0, UIFontWeightRegular), UIColor.labelColor)
                if (log.throwable != null) text("\nHas a stack trace", caption(), colors.error.ui())
            }
            val time = (cell.accessoryView as? UILabel) ?: UILabel().also { cell.accessoryView = it }
            time.text = formatTime(log.timestamp)
            time.font = UIFont.systemFontOfSize(11.0)
            time.textColor = UIColor.tertiaryLabelColor
            time.sizeToFit()
            return cell
        }

        private inner class TableDelegate : NSObject(), UITableViewDelegateProtocol {
            override fun tableView(tableView: UITableView, didSelectRowAtIndexPath: NSIndexPath) {
                tableView.deselectRowAtIndexPath(didSelectRowAtIndexPath, animated = true)
                val row = didSelectRowAtIndexPath.row.toInt()
                if (logsTab) {
                    shownLogs.getOrNull(row)?.let(::showLog)
                } else {
                    shown.getOrNull(row)?.let { showDetail(it.id) }
                }
            }
        }

        private inner class SearchUpdater : NSObject(), UISearchResultsUpdatingProtocol {
            override fun updateSearchResultsForSearchController(searchController: UISearchController) {
                val text = searchController.searchBar.text.orEmpty()
                if (logsTab) logQuery = text else query = text
                refresh()
            }
        }
    }

    private fun filterButton(): UIButton = UIButton.buttonWithType(UIButtonTypeSystem).apply {
        showsMenuAsPrimaryAction = true
        titleLabel?.font = UIFont.systemFontOfSize(14.0, UIFontWeightSemibold)
        layer.cornerRadius = 15.0
        heightAnchor.constraintEqualToConstant(30.0).setActive(true)
    }

    // endregion

    // region Log detail

    private fun showLog(log: LogEntity) {
        val controller = UIViewController(nibName = null, bundle = null)
        val text = UITextView(frame = controller.view.bounds)
        text.autoresizingMask = FLEXIBLE_SIZE
        text.setEditable(false)
        text.setSelectable(true)
        text.backgroundColor = UIColor.systemBackgroundColor
        text.textContainerInset = platform.UIKit.UIEdgeInsetsMake(16.0, 12.0, 32.0, 12.0)
        text.alwaysBounceVertical = true
        controller.view.addSubview(text)
        controller.navigationItem.title = log.tag
        controller.navigationItem.rightBarButtonItem = UIBarButtonItem(
            image = UIImage.systemImageNamed("doc.on.doc"),
            menu = UIMenu.menuWithTitle(
                title = "Copy",
                children = listOfNotNull(
                    action("Copy log", "doc.on.doc") { copy(log.copyText()) },
                    action("Copy message", "text.alignleft") { copy(log.message) },
                    log.throwable?.let { trace -> action("Copy stack trace", "exclamationmark.triangle") { copy(trace) } },
                ),
            ),
        )
        scope.launch {
            text.attributedText = withContext(Dispatchers.Default) {
                attributed {
                    text("${log.level}\n", mono(28.0, UIFontWeightBold), colors.levelColor(log.level).ui())
                    text(listOfNotNull(log.tag, log.layer, formatTimestamp(log.timestamp)).joinToString("  ·  ") + "\n", body(), UIColor.secondaryLabelColor)
                    section("Message")
                    text(log.message + "\n", mono(13.0, UIFontWeightRegular), UIColor.labelColor)
                    log.throwable?.let { trace ->
                        section("Stack trace")
                        text(trace + "\n", mono(12.0, UIFontWeightRegular), colors.error.ui())
                    }
                }
            }
        }
        (root as UINavigationController).pushViewController(controller, animated = true)
    }

    private fun LogEntity.copyText(): String = buildString {
        append("${levelLetter(level)}/$tag")
        layer?.let { append(" [$it]") }
        append(": ").append(message)
        throwable?.let { append('\n').append(it) }
    }

    // endregion

    // region Detail

    private fun showDetail(recordId: String) {
        val controller = UIViewController(nibName = null, bundle = null)
        val text = UITextView(frame = controller.view.bounds)
        text.autoresizingMask = FLEXIBLE_SIZE
        text.setEditable(false)
        text.setSelectable(true)
        text.backgroundColor = UIColor.systemBackgroundColor
        text.textContainerInset = platform.UIKit.UIEdgeInsetsMake(16.0, 12.0, 32.0, 12.0)
        text.alwaysBounceVertical = true
        controller.view.addSubview(text)

        val tabs = UISegmentedControl(items = listOf("Overview", "Request", "Response"))
        tabs.selectedSegmentIndex = 0
        controller.navigationItem.titleView = tabs

        var record: RecordEntity? = null
        fun render() {
            val r = record ?: return
            val tab = tabs.selectedSegmentIndex.toInt()
            scope.launch {
                // Highlighting large bodies happens off the main thread.
                val doc = withContext(Dispatchers.Default) { document(r, tab) }
                text.attributedText = doc
            }
        }
        tabs.addAction(UIAction.actionWithHandler { _ -> render() }, forControlEvents = UIControlEventValueChanged)

        controller.navigationItem.rightBarButtonItem = UIBarButtonItem(
            image = UIImage.systemImageNamed("doc.on.doc"),
            menu = UIMenu.menuWithTitle(
                title = "Copy",
                children = listOf(
                    action("Copy as cURL", "terminal") { record?.let { copy(it.toCurl()) } },
                    action("Copy URL", "link") { record?.let { copy(it.url) } },
                    action("Copy request body", "arrow.up.doc") { record?.requestBody?.let(::copy) },
                    action("Copy response body", "arrow.down.doc") { record?.responseBody?.let(::copy) },
                ),
            ),
        )

        detailJob?.cancel()
        detailJob = scope.launch {
            runtime.database.records().observe(recordId).collect {
                record = it
                render()
            }
        }
        (root as UINavigationController).pushViewController(controller, animated = true)
    }

    /** Builds the attributed document for one tab. Pure, so it can run off the main thread. */
    private fun document(r: RecordEntity, tab: Int): NSAttributedString = attributed {
        val state = r.callState
        val statusColor = colors.statusColor(state, r.statusCode).ui()
        when (tab) {
            0 -> {
                text(
                    when (state) {
                        CallState.Pending -> "In flight"
                        CallState.Failed -> "Failed"
                        CallState.Cancelled -> "Cancelled"
                        CallState.Complete -> "${r.statusCode ?: ""} ${r.statusText.orEmpty()}".trim()
                    } + "\n",
                    mono(28.0, UIFontWeightBold),
                    statusColor,
                )
                text("${r.method}  ·  ${formatDuration(r.durationMs)}  ·  ${formatBytes(r.responseBodySize)}\n", body(), UIColor.secondaryLabelColor)
                r.error?.let { text("\n$it\n", mono(13.0, UIFontWeightRegular), colors.error.ui()) }
                section("URL")
                text(r.url + "\n", mono(13.0, UIFontWeightRegular), UIColor.labelColor)

                val send = r.requestSentAt?.let { it - r.startedAt }?.coerceAtLeast(0)
                val wait = if (r.requestSentAt != null && r.responseStartedAt != null) (r.responseStartedAt - r.requestSentAt).coerceAtLeast(0) else null
                val receive = if (r.responseStartedAt != null && r.completedAt != null) (r.completedAt - r.responseStartedAt).coerceAtLeast(0) else null
                section("Timing")
                listOf(Triple("Send", send, colors.redirect), Triple("Wait", wait, colors.warn), Triple("Receive", receive, colors.ok))
                    .filter { it.second != null }
                    .forEach { (label, ms, color) ->
                        text("● ", body(), color.ui())
                        text("${label.padEnd(9)}${formatDuration(ms)}\n", mono(13.0, UIFontWeightRegular), UIColor.labelColor)
                    }
                section("Details")
                listOf(
                    "Started" to formatTimestamp(r.startedAt),
                    "Protocol" to (r.protocol ?: "—"),
                    "Request" to formatBytes(r.requestBodySize),
                    "Response" to formatBytes(r.responseBodySize),
                    "Endpoint" to (r.endpointKey ?: "—"),
                ).forEach { (k, v) ->
                    text("$k\n", caption(), UIColor.secondaryLabelColor)
                    text("$v\n", mono(13.0, UIFontWeightRegular), UIColor.labelColor)
                }
            }
            else -> {
                val request = tab == 1
                if (!request && r.statusCode == null && state != CallState.Complete) {
                    text(if (state == CallState.Pending) "Waiting for the response…" else (r.error ?: "No response"), body(), statusColor)
                    return@attributed
                }
                val headers = Codecs.decodeHeaders(if (request) r.requestHeaders else r.responseHeaders)
                section("Headers · ${headers.size}")
                if (headers.isEmpty()) text("None\n", body(), UIColor.secondaryLabelColor)
                headers.forEach { (k, v) ->
                    text("$k\n", caption(), UIColor.secondaryLabelColor)
                    text("$v\n", mono(13.0, UIFontWeightRegular), UIColor.labelColor)
                }
                val bodyText = if (request) r.requestBody else r.responseBody
                val size = if (request) r.requestBodySize else r.responseBodySize
                val type = (if (request) r.requestContentType else r.responseContentType)?.substringBefore(';')
                section("Body · ${formatBytes(size)}" + (type?.let { " · $it" } ?: ""))
                (if (request) r.requestBodyNote else r.responseBodyNote)?.let { text("$it\n", caption(), UIColor.secondaryLabelColor) }
                if (bodyText == null) {
                    text("Empty\n", body(), UIColor.secondaryLabelColor)
                } else {
                    val pretty = Codecs.prettyJsonOrNull(bodyText)
                    val json = pretty != null || looksLikeJsonLines(bodyText)
                    val lines = (pretty ?: bodyText).split('\n')
                    lines.take(MAX_LINES).forEach { line -> code(line, json, mono(12.0, UIFontWeightRegular)) }
                    if (lines.size > MAX_LINES) text("\n… ${lines.size - MAX_LINES} more lines. Use Copy for the full body.\n", caption(), UIColor.secondaryLabelColor)
                }
            }
        }
    }

    // endregion

    // region Helpers

    private inner class NavigationDelegate : NSObject(), UINavigationControllerDelegateProtocol {
        override fun navigationController(
            navigationController: UINavigationController,
            didShowViewController: UIViewController,
            animated: Boolean,
        ) {
            // Back on the list: stop observing the detail record.
            if (didShowViewController === calls.controller) detailJob?.cancel()
        }
    }

    private fun action(title: String, symbol: String, destructive: Boolean = false, handler: () -> Unit): UIAction =
        UIAction.actionWithTitle(title = title, image = UIImage.systemImageNamed(symbol), identifier = null, handler = { _ -> handler() })
            .apply { if (destructive) attributes = UIMenuElementAttributesDestructive }

    private fun copy(text: String) {
        UIPasteboard.generalPasteboard.string = text
    }

    private fun statusLabel(r: RecordSummary): String = when (r.callState) {
        CallState.Pending -> "…"
        CallState.Failed -> "ERR"
        CallState.Cancelled -> "CXL"
        CallState.Complete -> r.statusCode?.toString() ?: "—"
    }

    private fun mono(size: Double, weight: Double): UIFont = UIFont.monospacedSystemFontOfSize(size, weight)
    private fun body(): UIFont = UIFont.systemFontOfSize(15.0)
    private fun caption(): UIFont = UIFont.systemFontOfSize(12.0, UIFontWeightSemibold)

    private inner class Doc {
        val out = NSMutableAttributedString()

        fun text(s: String, font: UIFont, color: UIColor) {
            out.appendAttributedString(
                NSAttributedString.create(string = s, attributes = mapOf<Any?, Any?>(NSFontAttributeName to font, NSForegroundColorAttributeName to color)),
            )
        }

        fun section(title: String) {
            text("\n${title.uppercase()}\n", caption(), UIColor.tertiaryLabelColor)
        }

        fun code(line: String, json: Boolean, font: UIFont) {
            var cursor = 0
            if (json) {
                jsonTokens(line).forEach { token ->
                    if (token.start > cursor) text(line.substring(cursor, token.start), font, UIColor.labelColor)
                    text(line.substring(token.start, token.end), font, colors.tokenColor(token.kind).ui())
                    cursor = token.end
                }
            }
            if (cursor < line.length) text(line.substring(cursor), font, UIColor.labelColor)
            text("\n", font, UIColor.labelColor)
        }
    }

    private fun attributed(build: Doc.() -> Unit): NSAttributedString = Doc().apply(build).out

    private fun Long.ui(): UIColor = uiColor(this)

    private fun kotlinx.cinterop.CValue<platform.CoreGraphics.CGRect>.width(): Double = useContents { size.width }

    // endregion

    companion object {
        private const val CELL = "kotlinspect.call"
        private const val LOG_CELL = "kotlinspect.log"
        private const val PREVIEW_CHARS = 400
        private const val MAX_LINES = 3_000
        private val FLEXIBLE_SIZE = platform.UIKit.UIViewAutoresizingFlexibleWidth or platform.UIKit.UIViewAutoresizingFlexibleHeight
        private val DISPLAY_INLINE = platform.UIKit.UIMenuOptionsDisplayInline

        /** UIKit menus and bar button initializers used here need iOS 14. */
        fun isSupported(): Boolean = UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull()?.let { it >= 14 } ?: false
    }
}
