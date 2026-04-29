package io.synctuary.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.synctuary.android.ui.debug.PairingTestScreen
import io.synctuary.android.ui.theme.SynctuaryTheme

/**
 * Single-Activity entry point. Per the Android Architecture guide we host the
 * entire navigation graph inside one Activity backed by NavHost (added in a
 * later phase).
 *
 * Phase 2 lands the crypto + network + pairing layers and a single debug
 * screen ([PairingTestScreen]) that exercises the full §4.2 / §4.3 flow
 * end-to-end. The polished onboarding UI (mockup screens 1-3) lands in
 * Phase 2.2 along with NavHost.
 *
 * In release builds we still show the splash — debug screens are debug-only.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SynctuaryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (BuildConfig.DEBUG) {
                        PairingTestScreen()
                    } else {
                        SynctuarySplash()
                    }
                }
            }
        }
    }
}

/**
 * Brand splash mirroring the hero glyph from `docs/android-ui-mockups.html`
 * screen 1 — a rounded purple gradient tile with the wordmark beneath.
 *
 * Replaced by the onboarding NavHost in Phase 2; kept simple here so the
 * skeleton is verifiably wired without pulling in navigation dependencies
 * mid-build.
 */
@Composable
private fun SynctuarySplash() {
    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF4F378B),
                                    Color(0xFF6E50B5),
                                    Color(0xFF9A7DD9),
                                ),
                            ),
                        ),
                )
                Text(
                    text = "Synctuary",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "v0.4.0 · PROTOCOL 0.2.3",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF141218)
@Composable
private fun SplashPreview() {
    SynctuaryTheme {
        SynctuarySplash()
    }
}
