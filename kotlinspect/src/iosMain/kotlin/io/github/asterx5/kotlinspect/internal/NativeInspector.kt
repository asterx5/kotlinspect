@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)

package io.github.asterx5.kotlinspect.internal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import io.github.asterx5.kotlinspect.CallState
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.github.asterx5.kotlinspect.internal.db.RecordSummary
import io.github.asterx5.kotlinspect.internal.db.SessionEntity
import io.github.asterx5.kotlinspect.internal.ui.KsColors
import io.github.asterx5.kotlinspect.internal.ui.StatusFilter
import io.github.asterx5.kotlinspect.internal.ui.filterRecords
import io.github.asterx5.kotlinspect.internal.ui.highlight
import io.github.asterx5.kotlinspect.internal.ui.ksColors
import io.github.asterx5.kotlinspect.internal.ui.looksLikeJsonLines
import io.github.asterx5.kotlinspect.internal.ui.methodColor
import io.github.asterx5.kotlinspect.internal.ui.statusColor
import io.github.asterx5.kotlinspect.internal.ui.toCurl
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
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UIDevice
import platform.UIKit.UIFont
import platform.UIKit.UIFontWeightBold
import platform.UIKit.UIFontWeightMedium
import platform.UIKit.UIFontWeightRegular
import platform.UIKit.UIFontWeightSemibold
import platform.UIKit.UIImage
import platform.UIKit.UILabel
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
 * The inspector built from UIKit: a navigation controller with a searchable table of calls and a
 * detail screen. It reads the same database as the Compose inspector, so behavior is identical.
 */
internal class NativeInspector(private val runtime: KotlinspectRuntime, private val onClose: () -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val colors: KsColors = ksColors(
        dark = UIScreen.mainScreen.traitCollection.userInterfaceStyle == UIUserInterfaceStyle.UIUserInterfaceStyleDark,
    )

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
        private val filter = UISegmentedControl(items = StatusFilter.entries.map { it.label })
        private val stats = UILabel()
        private val empty = UILabel()
        private val search = UISearchController(searchResultsController = null)
        private val sessionsItem = UIBarButtonItem(image = UIImage.systemImageNamed("rectangle.stack"), menu = null)

        private val viewedSession = MutableStateFlow<String?>(null)
        private var all: List<RecordSummary> = emptyList()
        private var shown: List<RecordSummary> = emptyList()
        private var query = ""

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
            table.backgroundView = empty

            empty.text = "Waiting for traffic"
            empty.textAlignment = platform.UIKit.NSTextAlignmentCenter
            empty.textColor = UIColor.secondaryLabelColor

            // Header: status filter and stats line.
            val header = UIView(frame = CGRectMake(0.0, 0.0, UIScreen.mainScreen.bounds.width(), 76.0))
            filter.selectedSegmentIndex = 0
            filter.setFrame(CGRectMake(16.0, 8.0, UIScreen.mainScreen.bounds.width() - 32.0, 32.0))
            filter.autoresizingMask = platform.UIKit.UIViewAutoresizingFlexibleWidth
            filter.addAction(UIAction.actionWithHandler { _ -> refresh() }, forControlEvents = UIControlEventValueChanged)
            stats.setFrame(CGRectMake(16.0, 46.0, UIScreen.mainScreen.bounds.width() - 32.0, 22.0))
            stats.autoresizingMask = platform.UIKit.UIViewAutoresizingFlexibleWidth
            stats.font = UIFont.monospacedSystemFontOfSize(12.0, UIFontWeightMedium)
            stats.textColor = UIColor.secondaryLabelColor
            header.addSubview(filter)
            header.addSubview(stats)
            table.tableHeaderView = header

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

        private fun observe() {
            scope.launch {
                combine(runtime.database.sessions().observeAll(), runtime.currentSessionId, viewedSession) { sessions, current, viewed ->
                    Triple(sessions, current, viewed)
                }.collect { (sessions, current, viewed) ->
                    val id = viewed?.takeIf { v -> sessions.any { it.id == v } } ?: current
                    val session = sessions.firstOrNull { it.id == id }
                    controller.navigationItem.title = session?.name ?: "Kotlinspect"
                    controller.navigationItem.prompt = if (id == current) "● Recording" else "Past session"
                    sessionsItem.setMenu(sessionsMenu(sessions, id, current))
                }
            }
            scope.launch {
                combine(runtime.currentSessionId, viewedSession) { current, viewed -> viewed ?: current }
                    .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else runtime.database.records().observeSummaries(id) }
                    .collect { list ->
                        all = list
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
                    add(action("Clear calls", "eraser") { runtime.clearSession(viewedId) })
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
            val status = StatusFilter.entries.getOrElse(filter.selectedSegmentIndex.toInt()) { StatusFilter.All }
            shown = filterRecords(all, query, status, method = null)
            val errors = all.count { it.callState == CallState.Failed || (it.statusCode ?: 0) >= 400 }
            val done = all.mapNotNull { it.durationMs }
            stats.text = "${all.size} calls  ·  $errors errors  ·  ${formatBytes(all.sumOf { it.responseBodySize ?: 0 })}" +
                if (done.isEmpty()) "" else "  ·  avg ${formatDuration(done.sum() / done.size)}"
            empty.text = if (all.isEmpty()) "Waiting for traffic" else "Nothing matches"
            empty.hidden = shown.isNotEmpty()
            table.reloadData()
        }

        private inner class DataSource : NSObject(), UITableViewDataSourceProtocol {
            override fun tableView(tableView: UITableView, numberOfRowsInSection: NSInteger): NSInteger = shown.size.toLong()

            override fun tableView(tableView: UITableView, cellForRowAtIndexPath: NSIndexPath): UITableViewCell {
                val cell = tableView.dequeueReusableCellWithIdentifier(CELL)
                    ?: UITableViewCell(style = UITableViewCellStyle.UITableViewCellStyleSubtitle, reuseIdentifier = CELL)
                val r = shown[cellForRowAtIndexPath.row.toInt()]
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
        }

        private inner class TableDelegate : NSObject(), UITableViewDelegateProtocol {
            override fun tableView(tableView: UITableView, didSelectRowAtIndexPath: NSIndexPath) {
                tableView.deselectRowAtIndexPath(didSelectRowAtIndexPath, animated = true)
                val r = shown.getOrNull(didSelectRowAtIndexPath.row.toInt()) ?: return
                showDetail(r.id)
            }
        }

        private inner class SearchUpdater : NSObject(), UISearchResultsUpdatingProtocol {
            override fun updateSearchResultsForSearchController(searchController: UISearchController) {
                query = searchController.searchBar.text.orEmpty()
                refresh()
            }
        }
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
                    val lines = highlight(pretty ?: bodyText, json = pretty != null || looksLikeJsonLines(bodyText), colors = colors).lines
                    lines.take(MAX_LINES).forEach { line -> annotated(line, mono(12.0, UIFontWeightRegular)) }
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

        fun annotated(line: AnnotatedString, font: UIFont) {
            var cursor = 0
            line.spanStyles.sortedBy { it.start }.forEach { span ->
                if (span.start > cursor) text(line.text.substring(cursor, span.start), font, UIColor.labelColor)
                text(line.text.substring(span.start, span.end), font, span.item.color.ui())
                cursor = span.end
            }
            if (cursor < line.text.length) text(line.text.substring(cursor), font, UIColor.labelColor)
            text("\n", font, UIColor.labelColor)
        }
    }

    private fun attributed(build: Doc.() -> Unit): NSAttributedString = Doc().apply(build).out

    private fun Color.ui(): UIColor = UIColor(red = red.toDouble(), green = green.toDouble(), blue = blue.toDouble(), alpha = alpha.toDouble())

    private fun kotlinx.cinterop.CValue<platform.CoreGraphics.CGRect>.width(): Double = useContents { size.width }

    // endregion

    companion object {
        private const val CELL = "kotlinspect.call"
        private const val MAX_LINES = 3_000
        private val FLEXIBLE_SIZE = platform.UIKit.UIViewAutoresizingFlexibleWidth or platform.UIKit.UIViewAutoresizingFlexibleHeight
        private val DISPLAY_INLINE = platform.UIKit.UIMenuOptionsDisplayInline

        /** UIKit menus and bar button initializers used here need iOS 14. */
        fun isSupported(): Boolean = UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull()?.let { it >= 14 } ?: false
    }
}
