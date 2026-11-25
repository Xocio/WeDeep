package com.zeroui.template.feature.wechat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import com.zeroui.kit.components.AccentBanner
import com.zeroui.kit.components.ArrowPreference
import com.zeroui.kit.components.IconHue
import com.zeroui.kit.components.ListCard
import com.zeroui.kit.components.ListRow
import com.zeroui.kit.components.PreferenceIcon
import com.zeroui.kit.components.SettingIcon
import com.zeroui.template.wechat.Severance
import com.zeroui.template.wechat.SeveredFriend
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.RemoveContact
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zeroui.kit.components.ButtonVariant
import com.zeroui.kit.components.CardSlice
import com.zeroui.kit.components.ManagedBottomSheet
import com.zeroui.kit.components.SectionTitle
import com.zeroui.kit.components.SlicePosition
import com.zeroui.kit.components.TabRow
import com.zeroui.kit.components.Text
import com.zeroui.kit.components.TextButton
import com.zeroui.kit.components.ZeroPageFrame
import com.zeroui.kit.theme.ZeroTheme
import com.zeroui.template.R
import com.zeroui.template.core.appViewModel
import com.zeroui.template.navigation.AppRoute
import com.zeroui.template.wechat.Contact
import com.zeroui.template.wechat.WeChatHub
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Contacts sorted under one index letter. */
@Immutable
data class ContactGroup(val letter: Char, val members: List<Contact>)

@Immutable
data class ContactsUi(
    val loading: Boolean = true,
    val hasAccount: Boolean = true,
    val ready: Boolean = false,
    val hash: String? = null,
    val people: List<ContactGroup> = emptyList(),
    val groups: List<ContactGroup> = emptyList(),
    val peopleCount: Int = 0,
    val groupCount: Int = 0,
    val severed: List<SeveredFriend> = emptyList(),
    val suspected: List<Contact> = emptyList(),
    val markUnreliable: Boolean = false,
    val byName: Map<String, Contact> = emptyMap(),
)

class ContactsViewModel(private val hub: WeChatHub) : ViewModel() {
    private val state = MutableStateFlow(ContactsUi())
    val ui: StateFlow<ContactsUi> = state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(hub.current, hub.revision) { hash, _ -> hash }.collectLatest { hash ->
                state.value = withContext(Dispatchers.IO) { load(hash) }
            }
        }
    }

    private fun load(hash: String?): ContactsUi {
        if (hash == null) return ContactsUi(loading = false, hasAccount = false)
        val db = hub.db(hash) ?: return ContactsUi(loading = false, hash = hash)
        val self = hub.workspace.readMeta(hash)?.selfUser?.takeIf { it.isNotBlank() }
        val all = runCatching { db.contacts(self) }.getOrDefault(emptyList())
        val (rooms, people) = all.partition { it.isGroup }
        val byName = all.associateBy { it.username }
        val severed = runCatching { db.severedFriends() }.getOrDefault(emptyList()).filter { it.username in byName }
        val confirmed = severed.mapTo(HashSet()) { it.username }
        val marked = people.filter { it.strangerMark && it.username !in confirmed }
        val unreliable = people.size >= 10 && marked.size * 10 > people.size * 6
        return ContactsUi(
            loading = false,
            ready = true,
            hash = hash,
            people = indexed(people),
            groups = indexed(rooms),
            peopleCount = people.size,
            groupCount = rooms.size,
            severed = severed,
            suspected = if (unreliable) emptyList() else marked,
            markUnreliable = unreliable,
            byName = byName,
        )
    }

    private fun indexed(list: List<Contact>): List<ContactGroup> {
        val collator = Collator.getInstance(Locale.CHINA)
        return list
            .groupBy { initialOf(it.name, collator) }
            .toSortedMap(compareBy { if (it == '#') '~' else it })
            .map { (letter, rows) -> ContactGroup(letter, rows.sortedWith { a, b -> collator.compare(a.name, b.name) }) }
    }
}

/** First character of each pinyin initial in Chinese collation order, paired with its letter. */
private val PinyinStarts = listOf(
    'A' to "阿", 'B' to "芭", 'C' to "擦", 'D' to "搭", 'E' to "蛾", 'F' to "发", 'G' to "噶", 'H' to "哈",
    'J' to "击", 'K' to "喀", 'L' to "垃", 'M' to "妈", 'N' to "拿", 'O' to "哦", 'P' to "啪", 'Q' to "期",
    'R' to "然", 'S' to "撒", 'T' to "塌", 'W' to "挖", 'X' to "昔", 'Y' to "压", 'Z' to "匝",
)

/** Index letter of [name]: the Latin initial, or the pinyin initial of a Chinese first character, or '#'. */
private fun initialOf(name: String, collator: Collator): Char {
    val first = name.trim().firstOrNull() ?: return '#'
    if (first in 'a'..'z' || first in 'A'..'Z') return first.uppercaseChar()
    if (Character.UnicodeScript.of(first.code) != Character.UnicodeScript.HAN) return '#'
    val text = first.toString()
    var letter = '#'
    for ((l, start) in PinyinStarts) {
        if (collator.compare(text, start) >= 0) letter = l else break
    }
    return letter
}

/** Address book tab: friends and groups, indexed by initial. */
@Composable
fun ContactsScreen(bottomPadding: Dp, onNavigate: (AppRoute) -> Unit) {
    val vm = appViewModel { ContactsViewModel(wechat) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf<Contact?>(null) }
    var showSevered by remember { mutableStateOf(false) }
    val severedKinds = remember(ui.severed, ui.suspected) {
        ui.suspected.associate { it.username to Severance.Suspected } + ui.severed.associate { it.username to it.kind }
    }

    ZeroPageFrame(title = stringResource(R.string.wd_tab_contacts), bottomInset = bottomPadding) { padding ->
        when {
            ui.loading -> LoadingBox(Modifier.padding(padding))
            !ui.hasAccount -> AddDataPanel(padding, onOpenAccounts = { onNavigate(AppRoute.Accounts) })
            !ui.ready -> StateMessage(stringResource(R.string.wd_contacts_not_ready), modifier = Modifier.padding(padding))
            else -> ContactList(
                groups = if (tab == 0) ui.people else ui.groups,
                hash = ui.hash,
                padding = padding,
                severed = severedKinds,
                header = {
                    Column {
                        TabRow(
                            tabs = listOf(
                                stringResource(R.string.wd_contacts_people, ui.peopleCount),
                                stringResource(R.string.wd_contacts_groups, ui.groupCount),
                            ),
                            selectedTabIndex = tab,
                            onTabSelected = { tab = it },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        if (tab == 0) {
                            ListCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                ArrowPreference(
                                    title = stringResource(R.string.wd_severed_title),
                                    summary = if (ui.severed.isEmpty() && ui.suspected.isEmpty()) {
                                        stringResource(R.string.wd_severed_none)
                                    } else {
                                        stringResource(R.string.wd_severed_found, ui.severed.size, ui.suspected.size)
                                    },
                                    startAction = { PreferenceIcon(SeveredIcon) },
                                    onClick = { showSevered = true },
                                )
                            }
                        }
                    }
                },
                onOpen = { opened = it },
            )
        }
    }

    if (showSevered) {
        ManagedBottomSheet(onDismiss = { showSevered = false }, title = stringResource(R.string.wd_severed_title)) {
            SeveredList(
                severed = ui.severed,
                suspected = ui.suspected,
                markUnreliable = ui.markUnreliable,
                contacts = ui.byName,
                hash = ui.hash,
                onOpen = { c -> close { showSevered = false; opened = c } },
            )
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
    opened?.let { contact ->
        ManagedBottomSheet(onDismiss = { opened = null }) {
            ContactCard(
                contact = contact,
                hash = ui.hash,
                severed = severedKinds[contact.username],
                onChat = { close { opened = null; onNavigate(AppRoute.Chat(contact.username)) } },
            )
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

private val SeveredIcon = SettingIcon({ MiuixIcons.RemoveContact }, { MiuixIcons.RemoveContact }, IconHue.Red)

/** Text under a contact: the WeChat ID or the account id, plus the member count of a group. */
private fun uidLine(c: Contact): String = buildString {
    append(c.alias.ifBlank { c.username })
    if (c.isGroup && c.members > 0) append(" · ").append(c.members).append(" 人")
}

@Composable
private fun severedLabel(kind: Severance): String = stringResource(
    when (kind) {
        Severance.Blocked -> R.string.wd_severed_blocked
        Severance.Deleted -> R.string.wd_severed_deleted
        Severance.Suspected -> R.string.wd_severed_suspected
    },
)

/** Tag color of a severance level: red when a notice confirms it, amber for a profile mark. */
@Composable
private fun severedTone(kind: Severance): Color = if (kind == Severance.Suspected) IconHue.Orange.color else ZeroTheme.colors.danger

/** Confirmed friends first, newest notice first, then friends with a profile mark only. */
@Composable
private fun SeveredList(
    severed: List<SeveredFriend>,
    suspected: List<Contact>,
    markUnreliable: Boolean,
    contacts: Map<String, Contact>,
    hash: String?,
    onOpen: (Contact) -> Unit,
) {
    Column {
        SheetNote(stringResource(R.string.wd_severed_how))
        if (markUnreliable) {
            SheetNote(stringResource(R.string.wd_severed_mark_off), Modifier.padding(top = 8.dp), color = IconHue.Orange.color)
        }
        if (severed.isEmpty() && suspected.isEmpty()) {
            AccentBanner(stringResource(R.string.wd_severed_none), Modifier.fillMaxWidth().padding(top = 16.dp))
            return@Column
        }
        LazyColumn(Modifier.heightIn(max = 460.dp).padding(top = 8.dp)) {
            if (severed.isNotEmpty()) {
                item(key = "confirmed") { SheetSectionTitle(stringResource(R.string.wd_severed_confirmed, severed.size)) }
                itemsIndexed(severed, key = { _, s -> "c_" + s.username }) { index, s ->
                    val c = contacts[s.username] ?: return@itemsIndexed
                    CardSlice(position = SlicePosition.of(index, severed.size), onClick = { onOpen(c) }) {
                        ListRow(
                            title = c.name,
                            summary = severedLabel(s.kind) + " · " + formatChatTime(s.time),
                            startAction = { Avatar(c.name, size = 40.dp, hash = hash, username = c.username, modifier = Modifier.padding(end = 12.dp)) },
                        )
                    }
                }
            }
            if (suspected.isNotEmpty()) {
                item(key = "suspected") { SheetSectionTitle(stringResource(R.string.wd_severed_suspected_title, suspected.size)) }
                itemsIndexed(suspected, key = { _, c -> "s_" + c.username }) { index, c ->
                    CardSlice(position = SlicePosition.of(index, suspected.size), onClick = { onOpen(c) }) {
                        ListRow(
                            title = c.name,
                            summary = uidLine(c),
                            startAction = { Avatar(c.name, size = 40.dp, hash = hash, username = c.username, modifier = Modifier.padding(end = 12.dp)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactList(
    groups: List<ContactGroup>,
    hash: String?,
    padding: androidx.compose.foundation.layout.PaddingValues,
    severed: Map<String, Severance>,
    header: @Composable () -> Unit,
    onOpen: (Contact) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val headerIndex = remember(groups) {
        var index = 1
        groups.associate { g -> g.letter to index.also { index += 1 + g.members.size + 1 } }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            item(key = "tabs") { header() }
            if (groups.isEmpty()) {
                item(key = "empty") { StateMessage(stringResource(R.string.wd_contacts_empty), Modifier.padding(top = 24.dp)) }
            }
            groups.forEach { group ->
                item(key = "h_${group.letter}") { SectionTitle(group.letter.toString()) }
                itemsIndexed(group.members, key = { _, c -> c.username }) { index, c ->
                    CardSlice(
                        position = SlicePosition.of(index, group.members.size),
                        modifier = Modifier.padding(start = 12.dp, end = 28.dp),
                        onClick = { onOpen(c) },
                    ) { ContactRow(c, hash, severed[c.username]) }
                }
                item(key = "s_${group.letter}") { Spacer(Modifier.height(4.dp)) }
            }
        }
        if (groups.size > 1) {
            LetterRail(
                letters = groups.map { it.letter },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                onPick = { letter -> headerIndex[letter]?.let { scope.launch { listState.scrollToItem(it) } } },
            )
        }
    }
}

@Composable
private fun ContactRow(c: Contact, hash: String?, severed: Severance?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.name, size = 42.dp, hash = hash, username = c.username)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (severed != null) {
                    Text(
                        severedLabel(severed),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = severedTone(severed),
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(severedTone(severed).copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                uidLine(c),
                fontSize = 12.sp,
                color = ZeroTheme.colors.contentMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Vertical strip of index letters; tapping or sliding over it jumps to that section. */
@Composable
private fun LetterRail(letters: List<Char>, modifier: Modifier = Modifier, onPick: (Char) -> Unit) {
    val haptic = LocalHapticFeedback.current
    var height by remember { mutableIntStateOf(1) }
    var active by remember { mutableStateOf<Char?>(null) }
    fun pick(y: Float) {
        val i = (y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)
        val letter = letters[i]
        if (letter != active) {
            active = letter
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onPick(letter)
        }
    }
    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        Column(
            modifier = Modifier
                .clip(CircleShape)
                .background(if (active != null) ZeroTheme.colors.contentMuted.copy(alpha = 0.12f) else Color.Transparent)
                .onSizeChanged { height = it.height.coerceAtLeast(1) }
                .pointerInput(letters) {
                    detectTapGestures(
                        onPress = { offset ->
                            pick(offset.y)
                            tryAwaitRelease()
                            active = null
                        },
                    )
                }
                .pointerInput(letters) {
                    detectVerticalDragGestures(
                        onDragStart = { pick(it.y) },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                    ) { change, _ -> pick(change.position.y) }
                }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEach { letter ->
                Text(
                    letter.toString(),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (letter == active) ZeroTheme.colors.accent else ZeroTheme.colors.contentMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(14.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = active != null,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.padding(end = 44.dp),
        ) {
            Box(
                modifier = Modifier.size(56.dp).clip(CircleShape).background(ZeroTheme.colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text((active ?: ' ').toString(), fontSize = 26.sp, fontWeight = FontWeight.Medium, color = ZeroTheme.colors.onAccent)
            }
        }
    }
}

/** Profile sheet of one contact; tapping a line copies its value. */
@Composable
private fun ContactCard(contact: Contact, hash: String?, severed: Severance?, onChat: () -> Unit) {
    val context = LocalContext.current
    val copied = stringResource(R.string.wd_copied)
    fun copy(value: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("id", value))
        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            Avatar(contact.name, size = 60.dp, hash = hash, username = contact.username)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(contact.name, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (severed != null) {
                    Text(severedLabel(severed), fontSize = 13.sp, color = severedTone(severed), modifier = Modifier.padding(top = 2.dp))
                } else if (contact.isGroup && contact.members > 0) {
                    Text(stringResource(R.string.wd_contact_members, contact.members), fontSize = 13.sp, color = ZeroTheme.colors.contentMuted, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        ListCard {
            if (contact.remark.isNotBlank() && contact.nickname.isNotBlank() && contact.nickname != contact.remark) {
                ValueRow(stringResource(R.string.wd_contact_nickname), contact.nickname) { copy(contact.nickname) }
            }
            if (contact.alias.isNotBlank()) {
                ValueRow(stringResource(R.string.wd_contact_id), contact.alias) { copy(contact.alias) }
            }
            ValueRow(stringResource(R.string.wd_contact_uid), contact.username) { copy(contact.username) }
        }
        TextButton(
            text = stringResource(R.string.wd_contact_open_chat),
            onClick = onChat,
            variant = ButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }
}

/** List row with a label on the left and the value on the right. */
@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit) {
    ListRow(
        title = label,
        onClick = onClick,
        endActions = {
            Text(
                value,
                fontSize = 14.sp,
                color = ZeroTheme.colors.contentMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        },
    )
}
