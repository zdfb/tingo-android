package io.github.tancheng33.tingo

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tancheng33.tingo.engine.AbiCheck
import io.github.tancheng33.tingo.settings.AppLocale
import io.github.tancheng33.tingo.settings.AppSettings
import io.github.tancheng33.tingo.settings.SharedPrefsStore
import io.github.tancheng33.tingo.ui.TingoRoot
import io.github.tancheng33.tingo.ui.theme.TingoTheme

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as TingoApplication).container

    /**
     * 在 attachBaseContext 里套用语言，保证第一次绘制起就是所选语言，
     * 不会闪一下系统语言再切换。
     */
    override fun attachBaseContext(newBase: Context) {
        val language = AppSettings(SharedPrefsStore(newBase)).language
        super.attachBaseContext(AppLocale.wrap(newBase, language))
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // calculateWindowSizeClass 是 @Composable，必须在组合内计算
            val wide = calculateWindowSizeClass(this@MainActivity).widthSizeClass != WindowWidthSizeClass.Compact
            TingoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!AbiCheck.isSupported()) {
                        UnsupportedDevice()
                    } else {
                        TingoRoot(
                            container = container,
                            wide = wide,
                            onLanguageChange = { language ->
                                if (language != container.settings.language) {
                                    container.settings.language = language
                                    // 语言是资源维度的配置，改完要重建界面才生效
                                    recreate()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun UnsupportedDevice() {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.unsupported_abi_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.unsupported_abi_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
