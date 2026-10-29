/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.aicontentfilter.AiContentFilterPolicy
import moe.rukamori.archivetune.aicontentfilter.FilterAiContentUseCase
import moe.rukamori.archivetune.aicontentfilter.LoadAiContentFilterPolicyUseCase
import moe.rukamori.archivetune.auth.SwitchSavedYouTubeAccountUseCase
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.home.FilterCommunityPreviewsUseCase
import moe.rukamori.archivetune.home.HomeAction
import moe.rukamori.archivetune.home.HomePresentationPreferences
import moe.rukamori.archivetune.home.HomeScreenState
import moe.rukamori.archivetune.home.LoadCommunityPlaylistPreviewsUseCase
import moe.rukamori.archivetune.home.ObserveHomePresentationPreferencesUseCase
import moe.rukamori.archivetune.home.PrepareCommunityHomePageUseCase
import moe.rukamori.archivetune.home.PreparedCommunityHomePage
import moe.rukamori.archivetune.home.QuickPicksDisplayMode
import moe.rukamori.archivetune.home.account.HomeAccountCoordinator
import moe.rukamori.archivetune.home.feed.HomeFeedCache
import moe.rukamori.archivetune.home.feed.HomeFeedCoordinator
import moe.rukamori.archivetune.home.quickpicks.HomeQuickPicksManager
import moe.rukamori.archivetune.home.recommendations.HomeLocalRecommendationsUseCase
import moe.rukamori.archivetune.home.recommendations.HomeSimilarRecommendationsUseCase
import moe.rukamori.archivetune.home.state.HomeStateComposer
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.pages.HomePage
import moe.rukamori.archivetune.utils.SavedAccount
import moe.rukamori.archivetune.utils.SyncUtils
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelCacheTest {

    private val context: Context = mockk(relaxed = true)
    private val database: MusicDatabase = mockk(relaxed = true)
    private val syncUtils: SyncUtils = mockk(relaxed = true)
    private val switchSavedYouTubeAccount: SwitchSavedYouTubeAccountUseCase = mockk(relaxed = true)
    private val observePreferences: ObserveHomePresentationPreferencesUseCase = mockk()
    private val loadAiPolicy: LoadAiContentFilterPolicyUseCase = mockk()
    private val filterAiContent = FilterAiContentUseCase()
    private val prepareCommunityUseCase: PrepareCommunityHomePageUseCase = mockk()
    private val loadCommunityUseCase: LoadCommunityPlaylistPreviewsUseCase = mockk(relaxed = true)
    private val filterCommunityUseCase: FilterCommunityPreviewsUseCase = mockk(relaxed = true)
    private val feedCache: HomeFeedCache = mockk(relaxed = true)

    private val testDispatcher = UnconfinedTestDispatcher()
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(YouTube)
        mockkStatic("moe.rukamori.archivetune.utils.DataStoreKt")

        every { context.dataStore.data } returns flowOf(emptyPreferences())
        every { any<DataStore<Preferences>>().get<Any>(any(), any()) } answers { thirdArg() }
        every { context.getString(R.string.filter_community_playlists) } returns "Плейлисты сообщества"
        every { context.getString(R.string.filter_playlists) } returns "Плейлисты"

        every { observePreferences() } returns flowOf(
            HomePresentationPreferences(
                showCategoryChips = true,
                quickPicksDisplayMode = QuickPicksDisplayMode.CARD,
                showTonalBackdrop = false,
            ),
        )
        coEvery { loadAiPolicy() } returns AiContentFilterPolicy.Disabled

        every { prepareCommunityUseCase(any(), any()) } answers {
            PreparedCommunityHomePage(homePage = firstArg(), communitySection = null)
        }
        every { prepareCommunityUseCase(any()) } answers {
            PreparedCommunityHomePage(homePage = firstArg(), communitySection = null)
        }

        coEvery { database.getBlockedArtistIds() } returns emptyList()
        every { database.quickPicks() } returns flowOf(emptyList())
        every { database.recentSongs(any()) } returns flowOf(emptyList())
        every { database.allSongs() } returns flowOf(emptyList())
        every { database.hasRelatedSongs(any()) } returns false
        every { database.forgottenFavorites() } returns flowOf(emptyList())
        every { database.mostPlayedSongs(any(), any(), any()) } returns flowOf(emptyList())
        every { database.mostPlayedAlbums(any(), any(), any()) } returns flowOf(emptyList())
        every { database.mostPlayedArtists(any()) } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun samplePage(title: String, songId: String): HomePage =
        HomePage(
            chips = null,
            sections = listOf(
                HomePage.Section(
                    title = title,
                    label = null,
                    thumbnail = null,
                    endpoint = null,
                    items = listOf(
                        SongItem(
                            id = songId,
                            title = "Track $songId",
                            artists = listOf(Artist("Artist", null)),
                            album = null,
                            duration = 100,
                            thumbnail = "https://thumb/$songId",
                            explicit = false,
                            endpoint = null,
                        ),
                    ),
                ),
            ),
            continuation = null,
        )

    private fun createViewModel(): HomeViewModel {
        val viewModel =
            HomeViewModel(
                context = context,
                syncUtils = syncUtils,
                observeHomePresentationPreferences = observePreferences,
                quickPicksManager = HomeQuickPicksManager(context, database),
                localRecommendations = HomeLocalRecommendationsUseCase(context, database),
                similarRecommendationsUseCase = HomeSimilarRecommendationsUseCase(context, database, loadAiPolicy, filterAiContent),
                accountCoordinator = HomeAccountCoordinator(context, syncUtils, switchSavedYouTubeAccount),
                feedCoordinator = HomeFeedCoordinator(
                    context = context,
                    database = database,
                    loadAiContentFilterPolicy = loadAiPolicy,
                    filterAiContent = filterAiContent,
                    prepareCommunityHomePage = prepareCommunityUseCase,
                    loadCommunityPlaylistPreviews = loadCommunityUseCase,
                    filterCommunityPreviews = filterCommunityUseCase,
                ),
                stateComposer = HomeStateComposer(),
                feedCache = feedCache,
            )
        viewModelStore.put("home-view-model-${System.nanoTime()}", viewModel)
        return viewModel
    }

    @Test
    fun startupCacheHydration_appliesCachedHome_beforeNetwork() = runTest {
        val cachedPage = samplePage("Cached Section", "song_cached")
        val networkPage = samplePage("Network Section", "song_network")

        val networkProceed = CompletableDeferred<Unit>()
        val networkDone = CompletableDeferred<Unit>()

        coEvery { feedCache.loadHomePage() } returns cachedPage
        coEvery { YouTube.home() } coAnswers {
            networkProceed.await()
            networkDone.complete(Unit)
            Result.success(networkPage)
        }

        val viewModel = createViewModel()
        val collectorJob = launch { viewModel.screenState.collect {} }

        try {
            // Activate WhileSubscribed and await Success with cached content
            val cachedSuccess = withTimeout(5000) {
                viewModel.screenState.filterIsInstance<HomeScreenState.Success>().first {
                    it.uiState.homePage?.sections?.any { s -> s.title == "Cached Section" } == true
                }
            }
            assertNotNull(cachedSuccess)
            assertTrue(viewModel.allYtItems.value.any { it.id == "song_cached" })

            // Allow network to proceed
            networkProceed.complete(Unit)
            networkDone.await()

            // Fresh network replaces cache
            val freshSuccess = withTimeout(5000) {
                viewModel.screenState.filterIsInstance<HomeScreenState.Success>().first {
                    it.uiState.homePage?.sections?.any { s -> s.title == "Network Section" } == true
                }
            }
            assertNotNull(freshSuccess)
            assertTrue(viewModel.allYtItems.value.any { it.id == "song_network" })
            coVerify { feedCache.saveHomePage(networkPage) }
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun cacheBeforeNetworkOrdering_networkCannotStartUntilHydrationCompletes() = runTest {
        val cachedPage = samplePage("Hydrated Page", "song_hydrated")
        val networkPage = samplePage("Network Page", "song_net")

        val cacheGate = CompletableDeferred<Unit>()
        val networkStarted = AtomicBoolean(false)

        coEvery { feedCache.loadHomePage() } coAnswers {
            cacheGate.await()
            cachedPage
        }
        coEvery { YouTube.home() } coAnswers {
            networkStarted.set(true)
            Result.success(networkPage)
        }

        val viewModel = createViewModel()
        val collectorJob = launch { viewModel.screenState.collect {} }

        try {
            // Network must not have started while cache hydration is suspended
            assertFalse(networkStarted.get())

            // Release cache gate
            cacheGate.complete(Unit)

            // Cache applied, then network completes
            withTimeout(5000) {
                viewModel.screenState.filterIsInstance<HomeScreenState.Success>().first {
                    it.uiState.homePage?.sections?.any { s -> s.title == "Network Page" } == true
                }
            }
            assertTrue(networkStarted.get())
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun accountSwitch_staleNetworkResponse_doesNotApply_or_persist() = runTest {
        val staleNetworkPage = samplePage("Stale Account Feed", "stale_song")
        val networkGate = CompletableDeferred<Unit>()
        val networkFinished = CompletableDeferred<Unit>()

        coEvery { feedCache.loadHomePage() } returns null
        coEvery { YouTube.home() } coAnswers {
            networkGate.await()
            networkFinished.complete(Unit)
            Result.success(staleNetworkPage)
        }

        val viewModel = createViewModel()
        val collectorJob = launch { viewModel.screenState.collect {} }

        try {
            // Switch account while network request is in flight
            val targetAccount = SavedAccount(
                id = "acc_2",
                name = "Account 2",
                email = "acc2@example.com",
                channelHandle = "@acc2",
                innerTubeCookie = "cookie_2",
                visitorData = "",
                dataSyncId = "sync_2",
                ytmSync = true,
                selectedYtmPlaylists = "",
            )
            viewModel.switchToAccount(targetAccount, forceSyncOnSwitch = false)

            // Old network finishes
            networkGate.complete(Unit)
            networkFinished.await()

            // Verify stale network response was never saved to feedCache or applied
            coVerify(exactly = 0) { feedCache.saveHomePage(staleNetworkPage) }
            assertFalse(viewModel.allYtItems.value.any { it.id == "stale_song" })
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun accountSwitch_racingWithInFlightSave_clearsCacheAndDoesNotRetainOldPage() = runTest {
        val oldPage = samplePage("Old Account Page", "old_song")
        val saveEntered = CompletableDeferred<Unit>()
        val saveRelease = CompletableDeferred<Unit>()

        coEvery { feedCache.loadHomePage() } returns null
        coEvery { YouTube.home() } returns Result.success(oldPage)
        coEvery { feedCache.saveHomePage(any()) } coAnswers {
            saveEntered.complete(Unit)
            saveRelease.await()
        }

        val viewModel = createViewModel()
        val collectorJob = launch { viewModel.screenState.collect {} }

        try {
            // Wait until saveHomePage is actively running
            saveEntered.await()

            // Concurrently switch account while save is suspended
            val newAccount = SavedAccount(
                id = "acc_new",
                name = "New Account",
                email = "new@example.com",
                channelHandle = "@new",
                innerTubeCookie = "cookie_new",
                visitorData = "",
                dataSyncId = "sync_new",
                ytmSync = true,
                selectedYtmPlaylists = "",
            )
            viewModel.switchToAccount(newAccount, forceSyncOnSwitch = false)

            // Release the save
            saveRelease.complete(Unit)

            // Switch clear must run after save finishes and clear must be invoked
            withTimeout(5000) {
                // Ensure feedCoordinator items are cleared
                while (viewModel.allYtItems.value.any { it.id == "old_song" }) {
                    kotlinx.coroutines.delay(10)
                }
            }
            coVerify(atLeast = 1) { feedCache.clear() }
            assertFalse(viewModel.allYtItems.value.any { it.id == "old_song" })
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun queuedRefresh_executesReload_whenLoadAlreadyRunning() = runTest {
        val firstPage = samplePage("First Load", "song_first")
        val secondPage = samplePage("Second Load", "song_second")

        val firstGate = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val secondDone = CompletableDeferred<Unit>()
        var callCount = 0

        coEvery { feedCache.loadHomePage() } returns null
        coEvery { YouTube.home() } coAnswers {
            callCount++
            if (callCount == 1) {
                firstStarted.complete(Unit)
                firstGate.await()
                Result.success(firstPage)
            } else {
                secondDone.complete(Unit)
                Result.success(secondPage)
            }
        }

        val viewModel = createViewModel()
        val collectorJob = launch { viewModel.screenState.collect {} }

        try {
            firstStarted.await()

            // User triggers refresh while first load is running
            viewModel.onAction(HomeAction.Refresh)

            // Release first load
            firstGate.complete(Unit)

            // Second queued pass must execute
            secondDone.await()
            assertEquals(2, callCount)

            withTimeout(5000) {
                viewModel.screenState.filterIsInstance<HomeScreenState.Success>().first {
                    it.uiState.homePage?.sections?.any { s -> s.title == "Second Load" } == true
                }
            }
            assertTrue(viewModel.allYtItems.value.any { it.id == "song_second" })
        } finally {
            collectorJob.cancel()
        }
    }
}
