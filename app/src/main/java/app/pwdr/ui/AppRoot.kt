package app.pwdr.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.pwdr.R
import app.pwdr.map.MapScreen
import app.pwdr.social.Social

private enum class Tab(val label: Int, val icon: Int) {
    MAP(R.string.tab_map, R.drawable.ic_tab_map),
    RECORD(R.string.tab_record, R.drawable.ic_tab_record),
    STATS(R.string.tab_stats, R.drawable.ic_tab_stats),
    FRIENDS(R.string.tab_friends, R.drawable.ic_tab_friends),
}

/** Untere Leiste, siehe PLAN.md 5a. Der Tab "Freunde" kommt mit Phase 4. */
@Composable
fun AppRoot() {
    var selected by rememberSaveable { mutableIntStateOf(Tab.RECORD.ordinal) }
    val invitations by Social.invitations.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = androidx.compose.ui.unit.Dp(0f),
            ) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab.ordinal,
                        onClick = { selected = tab.ordinal },
                        icon = {
                            if (tab == Tab.FRIENDS && invitations.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text(invitations.size.toString()) } }) {
                                    Icon(painterResource(tab.icon), contentDescription = null)
                                }
                            } else {
                                Icon(painterResource(tab.icon), contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(tab.label)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (Tab.entries[selected]) {
                Tab.MAP -> MapScreen()
                Tab.RECORD -> RecordScreen()
                Tab.STATS -> StatsScreen()
                Tab.FRIENDS -> FriendsScreen()
            }
        }
    }
}
