@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aurora.companion.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.companion.android.AuroraViewModel
import com.aurora.companion.android.BookInfo
import com.aurora.companion.android.Library
import com.aurora.companion.android.Passage
import com.aurora.companion.android.UiState
import com.aurora.companion.android.toNiceSize
import kotlinx.coroutines.launch

private data class ReaderTarget(val book: BookInfo, val byte: Long?)

/** 0.8 Library: optional download, index progress, book list / search, reader, "Knowledge from books" toggle. */
@Composable
fun LibraryScreen(ui: UiState, vm: AuroraViewModel, sizing: Sizing, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var reading by remember { mutableStateOf<ReaderTarget?>(null) }
    reading?.let { t -> BookReader(t.book, t.byte, ui, vm, onClose = { reading = null }); return }
    androidx.activity.compose.BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { vm.refreshLibrary() }
    val st = ui.library
    // 1.0: re-read when the library changes (a book added from Find a book shows up right away).
    val books = remember(st) { runCatching { Library.books(ctx) }.getOrDefault(emptyList()) }
    var tab by remember { mutableIntStateOf(0) }
    var findQuery by remember { mutableStateOf("") }
    // Chat asked to open a tab ("read me <book you don't have>" -> Find a book with the title filled in).
    LaunchedEffect(ui.libraryOpen?.id) {
        ui.libraryOpen?.let { o -> tab = if (o.tab == "find") 1 else 0; if (o.query.isNotBlank()) findQuery = o.query; vm.consumeLibraryOpen() }
    }
    var confirmRemove by remember { mutableStateOf<BookInfo?>(null) }
    confirmRemove?.let { b ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove this book?") },
            text = { Text("${b.title} (${b.bytes.toNiceSize()}) will be deleted from this device. You can add it again with Find a book.") },
            confirmButton = { TextButton(onClick = { confirmRemove = null; vm.removeAddedBook(b) }) { Text("Remove", color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
    var filter by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Passage>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var agreed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val task = ui.libraryTask

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete the library?") },
        text = { Text("Removes the downloaded books and the search index (${(st.downloadedBytes + st.indexBytes).toNiceSize()}). You can download them again any time.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteLibrary() }) { Text("Delete", color = Color(0xFFFF6B6B)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 860.dp).fillMaxSize()) {
            Row(Modifier.padding(start = sizing.pad, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("\uD83D\uDCDA Library", fontSize = (sizing.body + 8).sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            // One scrolling list for everything, so nothing gets cut off with big fonts / small screens.
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = sizing.pad, end = sizing.pad, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ----- download / index card -----
            item { Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    task != null -> {
                        Text(task.title, fontWeight = FontWeight.SemiBold)
                        val p = task.progress
                        if (p != null) LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text((if (p != null) "${(p * 100).toInt()}%  -  " else "") + task.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        Text("Keep the app open. You can pause and continue later; finished books are kept.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = vm::cancelLibraryTask) { Text("Pause / cancel") }
                    }
                    !st.complete -> {
                        Text("${st.totalBooks} public-domain books (Project Gutenberg): classics, myths, history, philosophy, religion, science, gardening, scouting and first aid.",
                            fontSize = 14.sp)
                        Text("Optional download: ${(st.totalBytes - st.downloadedBytes).toNiceSize()} of plain text from the Aurora beta's public GitHub repo, " +
                            "checksum-verified and saved in the app's private storage (the search index adds about 35-40 MB). Wi-Fi recommended." +
                            if (st.anyDownloaded) "  Already have ${st.downloadedBooks} of ${st.totalBooks}; it continues where it stopped." else "",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Public domain: these texts are public domain in the USA (Project Gutenberg). If you are outside the USA, check your local copyright laws. " +
                            "Old books reflect the views of their time.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { agreed = !agreed }) {
                            Checkbox(checked = agreed, onCheckedChange = { agreed = it }); Text("I understand", fontSize = 13.sp)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = vm::downloadLibrary, enabled = agreed) { Text("Download ${(st.totalBytes - st.downloadedBytes).toNiceSize()}") }
                            if (st.anyDownloaded) OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
                        }
                    }
                    !st.ready -> {
                        Text("Books downloaded. The search index isn't finished (${st.indexedBooks} of ${st.downloadedBooks} books).", fontSize = 14.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = vm::indexLibrary) { Text("Build index") }
                            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
                        }
                    }
                    else -> {
                        Text("\u2705 ${st.indexedBooks} books ready offline", fontWeight = FontWeight.SemiBold)
                        Text("Books ${st.downloadedBytes.toNiceSize()} + index ${st.indexBytes.toNiceSize()}  -  %,d passages".format(st.chunks),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { confirmDelete = true }, contentPadding = PaddingValues(0.dp)) { Text("Delete library", color = Color(0xFFFF6B6B)) }
                    }
                }
            } }
            // ----- knowledge toggle -----
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Knowledge from books", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("When you ask a question or name a book, ${ui.companion.name} gets up to 3 short passages (about 1,200 characters) and names the book. " +
                        "Off by default under 6 GB RAM, like the desktop app (this device: %.1f GB, default %s).".format(ui.ramGb, if (ui.bookKnowledgeDefault) "on" else "off") +
                        if (!st.ready) " Needs the library downloaded." else "",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = ui.bookKnowledge, onCheckedChange = vm::setBookKnowledge)
            } }
            // ----- tabs -----
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Books", "\uD83D\uDD0E Find a book", "Search inside books").forEachIndexed { i, label ->
                    if (tab == i) Button(onClick = { tab = i }) { Text(label) } else OutlinedButton(onClick = { tab = i }) { Text(label) }
                }
            } }
            if (tab == 0) {
                item { OutlinedTextField(filter, { filter = it.take(60) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Filter by title, author or topic") }) }
                val f = filter.trim().lowercase()
                val shown = books.filter { f.isEmpty() || it.title.lowercase().contains(f) || it.author.lowercase().contains(f) || it.category.lowercase().contains(f) || it.aliases.any { a -> a.contains(f) } }
                    .sortedWith(compareBy({ it.category }, { it.title.removePrefix("The ").lowercase() }))
                if (books.none { Library.isDownloaded(ctx, it) }) item {
                    Text("Tip: download the library above, or add a single book with \uD83D\uDD0E Find a book. Then tap \uD83D\uDD0A Read to me and ${ui.companion.name} reads it aloud.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                run {
                    items(shown, key = { it.file }) { b ->
                        val have = remember(st) { Library.isDownloaded(ctx, b) }
                        val playingThis = ui.reading?.file == b.file
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).clickable(enabled = have) { reading = ReaderTarget(b, null) }.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(b.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2)
                                    Text(listOf(b.author, b.category, b.bytes.toNiceSize()).filter { it.isNotBlank() }.joinToString("  -  "),
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                if (!have) Text("\u2014", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                            if (have) FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                                itemVerticalAlignment = Alignment.CenterVertically) {
                                // 1.0: "Read this to me" - the companion reads it aloud (continues where listening stopped).
                                // 1.0.2: reading happens on the chat screen (big avatar + book controls), so the Library closes.
                                Button(onClick = { if (vm.readAloud(b)) onClose() }, enabled = ui.voiceReady && !playingThis, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                                    Text(if (playingThis) "\uD83D\uDD0A Reading..." else if (vm.listenPos(b.file) > 0) "\uD83D\uDD0A Continue reading to me" else "\uD83D\uDD0A Read this to me", fontSize = 14.sp)
                                }
                                OutlinedButton(onClick = { reading = ReaderTarget(b, null) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text("\uD83D\uDCD6 Read myself", fontSize = 14.sp) }
                                if (b.added) TextButton(onClick = { confirmRemove = b }) { Text("Remove", fontSize = 13.sp, color = Color(0xFFFF6B6B)) }
                            }
                        }
                    }
                }
            } else if (tab == 1) {
                item { FindBook(ui, vm, findQuery, onQuery = { findQuery = it }, onRead = { b -> if (vm.readAloud(b)) onClose() }) }
            } else {
                val doSearch: () -> Unit = {
                    if (query.isNotBlank()) { searching = true; scope.launch { results = vm.searchBooks(query); searching = false } }
                }
                item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it.take(120) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. treat a burn") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { doSearch() }))
                    Button(onClick = doSearch, enabled = st.indexedBooks > 0 && !searching) { Text("Search") }
                    if (st.indexedBooks == 0) Text("Download the library first; search works on the on-device index.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val r0 = results
                    if (r0 != null && r0.isEmpty() && !searching) Text("No matches.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                val r = results
                run {
                    items(r ?: emptyList()) { p ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).clickable { reading = ReaderTarget(p.book, p.byteStart) }.padding(10.dp)) {
                            Text(p.book.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AuroraTeal)
                            Text(Library.excerpt(p.text, Library.keywords(query), 260), fontSize = 13.sp)
                        }
                    }
                }
            }
            }
            // 1.0: the read-aloud player while a book is open.
            ui.reading?.let { r -> ReadAloudPlayer(r, compact = false, modifier = Modifier.padding(horizontal = sizing.pad, vertical = 6.dp).navigationBarsPadding()) }
        }
    }
}

/**
 * 1.0 "Find a book": Project Gutenberg's free public-domain books through Gutendex (gutendex.com), only while Online
 * mode is on. Add downloads the plain text (or EPUB), removes Gutenberg's licence header and keeps it offline.
 */
@Composable
private fun FindBook(ui: UiState, vm: AuroraViewModel, query: String, onQuery: (String) -> Unit, onRead: (BookInfo) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<com.aurora.companion.android.Gutendex.Page?>(null) }
    var results by remember { mutableStateOf<List<com.aurora.companion.android.Gutendex.Result>>(emptyList()) }
    var sizes by remember { mutableStateOf<Map<Int, Long>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<Map<Int, Float?>>(emptyMap()) }
    var added by remember { mutableStateOf<Map<Int, BookInfo>>(emptyMap()) }
    val have = remember(ui.library) { Library.addedBooks(ctx).mapNotNull { b -> b.gutenberg?.let { it to b } }.toMap() }
    fun friendly(e: Throwable?): String = when {
        e is java.net.SocketTimeoutException || e is java.net.UnknownHostException || e is java.net.ConnectException ->
            "The book search isn't answering right now (no connection, or Project Gutenberg / Gutendex may be down for maintenance). Try again later."
        e?.message?.contains("HTTP 5") == true -> "Project Gutenberg / Gutendex is busy or down for maintenance right now. Try again later."
        else -> e?.message ?: "Something went wrong."
    }
    val doSearch: (String?) -> Unit = { next ->
        if (query.isNotBlank() && !busy) {
            busy = true; error = null
            scope.launch {
                val r = vm.findBooks(query, next)
                r.onSuccess { p ->
                    page = p
                    results = if (next == null) p.results else results + p.results
                    if (p.results.isEmpty() && next == null) error = "No free books found for \"$query\"."
                    busy = false
                    sizes = sizes + vm.bookSizes(p.results)
                }.onFailure { error = friendly(it); busy = false }
            }
        }
    }
    LaunchedEffect(query, ui.online) { if (ui.online && query.isNotBlank() && results.isEmpty() && page == null) doSearch(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Add free public-domain books from Project Gutenberg (gutenberg.org), searched with Gutendex. No account needed. " +
            "Added books are saved on this device, so reading them works offline.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!ui.online) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("\uD83C\uDF10 Find a book needs Online mode", fontWeight = FontWeight.SemiBold)
                Text("Aurora is offline, so nothing leaves this phone. To search, turn on Online: only your search words are sent to gutendex.com, " +
                    "and the book you pick is downloaded from gutenberg.org.", fontSize = 13.sp)
                Button(onClick = { vm.setOnline(true) }) { Text("Turn on Online") }
            }
            return@Column
        }
        OutlinedTextField(query, { onQuery(it.take(80)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Title or author, e.g. Peter Pan") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { results = emptyList(); page = null; doSearch(null) }))
        Button(onClick = { results = emptyList(); page = null; doSearch(null) }, enabled = query.isNotBlank() && !busy) { Text("Search") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }
        page?.let { p -> if (p.hidden > 0) Text(
            if (com.aurora.companion.android.Edition.UNLOCKED) "${p.hidden} result(s) hidden: not public domain in the USA."
            else "${p.hidden} result(s) hidden (not public domain in the USA, or tagged by Gutendex as adult subjects).",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        results.forEach { r ->
            val mine = added[r.id] ?: have[r.id]
            val prog = adding[r.id]
            val isAdding = adding.containsKey(r.id)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 3)
                val size = sizes[r.id]
                Text(listOf(r.author.ifBlank { "Unknown author" },
                    when { size == null -> "size..."; size > 0 -> size.toNiceSize(); else -> "" },
                    if (r.textUrl == null) "EPUB" else "").filter { it.isNotBlank() }.joinToString("  -  "),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    mine != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("\u2705 In your Library", fontSize = 13.sp, color = AuroraTeal)
                        Button(onClick = { onRead(mine) }, enabled = ui.voiceReady) { Text("\uD83D\uDD0A Read this to me") }
                    }
                    isAdding -> {
                        if (prog != null) LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Adding...", fontSize = 12.sp)
                    }
                    else -> Button(onClick = {
                        adding = adding + (r.id to null)
                        scope.launch {
                            val res = vm.addFoundBook(r) { f -> adding = adding + (r.id to f) }
                            adding = adding - r.id
                            res.onSuccess { b -> added = added + (r.id to b) }.onFailure { error = "${r.title}: " + friendly(it) }
                        }
                    }) { Text("+ Add") }
                }
            }
        }
        if (page?.next != null && !busy) OutlinedButton(onClick = { doSearch(page?.next) }) { Text("More results") }
        Text("Public domain in the USA. If you are outside the USA, check your local copyright laws before downloading. Old books reflect the views of their time.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Paged reader: page size follows the font size and screen; tap the left / right side or use the buttons to turn pages. */
@Composable
private fun BookReader(book: BookInfo, startByte: Long?, ui: UiState, vm: AuroraViewModel, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    var rt by remember { mutableStateOf<Library.ReaderText?>(null) }
    var failed by remember { mutableStateOf(false) }
    var pos by remember { mutableIntStateOf(-1) }   // character offset of the page start
    LaunchedEffect(book.file) {
        val r = vm.readerText(book)
        if (r == null) failed = true
        else { pos = (if (startByte != null) r.positionForByte(startByte) else vm.readerPos(book.file)).coerceIn(0, r.text.length); rt = r }
    }
    var font by remember { mutableIntStateOf(vm.readerFont()) }
    Box(Modifier.fillMaxSize().background(Color(0xFF0E0F12))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(book.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                    if (book.author.isNotBlank()) Text(book.author, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                // 1.0: read aloud from the page you're on.
                TextButton(onClick = { vm.readAloud(book, pos.coerceAtLeast(0)) }, enabled = ui.voiceReady && rt != null) { Text("\uD83D\uDD0A", fontSize = 18.sp) }
                TextButton(onClick = { if (font > 12) { font -= 2; vm.saveReaderFont(font) } }) { Text("A-", fontSize = 14.sp) }
                TextButton(onClick = { if (font < 34) { font += 2; vm.saveReaderFont(font) } }) { Text("A+", fontSize = 18.sp) }
                TextButton(onClick = onClose) { Text("Close") }
            }
            val text = rt?.text
            if (text == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (failed) Text("Couldn't open this book.") else CircularProgressIndicator()
                }
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val cols = maxWidth.value / (font * 0.52f)
                    val rows = maxHeight.value / (font * 1.5f)
                    val chars = (cols * rows * 0.8f).toInt().coerceAtLeast(150)
                    val pages = remember(text, chars) { Library.paginate(text, chars) }
                    // Page containing [pos].
                    var lo = 0; var hi = pages.size - 1
                    while (lo < hi) { val mid = (lo + hi + 1) / 2; if (pages[mid] <= pos) lo = mid else hi = mid - 1 }
                    val page = lo
                    val goTo: (Int) -> Unit = { p -> val q = p.coerceIn(0, pages.size - 1); pos = pages[q]; vm.saveReaderPos(book.file, pages[q]) }
                    Column(Modifier.fillMaxSize()) {
                        val end = if (page + 1 < pages.size) pages[page + 1] else text.length
                        Box(Modifier.weight(1f).fillMaxWidth().pointerInput(page, pages) {
                            detectTapGestures { o ->
                                if (o.x < size.width * 0.33f) goTo(page - 1) else if (o.x > size.width * 0.67f) goTo(page + 1)
                            }
                        }) {
                            Text(text.substring(pages[page], end), fontSize = font.sp, lineHeight = (font * 1.45f).sp, fontFamily = FontFamily.Serif,
                                color = Color(0xFFE8E4DA), modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState(0)))
                        }
                        var slider by remember(page, pages) { mutableFloatStateOf(page.toFloat()) }
                        if (pages.size > 1) Slider(value = slider, onValueChange = { slider = it }, onValueChangeFinished = { goTo(slider.toInt()) },
                            valueRange = 0f..(pages.size - 1).toFloat())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { goTo(page - 1) }, enabled = page > 0) { Text("\u2039 Prev") }
                            Text("Page ${page + 1} of ${pages.size}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            OutlinedButton(onClick = { goTo(page + 1) }, enabled = page < pages.size - 1) { Text("Next \u203A") }
                        }
                    }
                }
            }
            // 1.0: the player follows you into the reader.
            ui.reading?.let { r -> ReadAloudPlayer(r, compact = true, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}
