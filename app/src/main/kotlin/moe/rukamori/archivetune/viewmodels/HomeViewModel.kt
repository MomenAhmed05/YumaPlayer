/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.aicontentfilter.FilterAiContentUseCase
import moe.rukamori.archivetune.aicontentfilter.LoadAiContentFilterPolicyUseCase
import moe.rukamori.archivetune.auth.SwitchSavedYouTubeAccountUseCase
import moe.rukamori.archivetune.constants.SpeedDialSongIdsKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.Album
import moe.rukamori.archivetune.db.entities.LocalItem
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.home.FilterCommunityPreviewsUseCase
import moe.rukamori.archivetune.home.HomeAction
import moe.rukamori.archivetune.home.HomeScreenState
import moe.rukamori.archivetune.home.LoadCommunityPlaylistPreviewsUseCase
import moe.rukamori.archivetune.home.ObserveHomePresentationPreferencesUseCase
import moe.rukamori.archivetune.home.PrepareCommunityHomePageUseCase
import moe.rukamori.archivetune.home.account.HomeAccountCoordinator
import moe.rukamori.archivetune.home.feed.HomeFeedCoordinator
import moe.rukamori.archivetune.home.quickpicks.HomeQuickPicksManager
import moe.rukamori.archivetune.home.recommendations.HomeLocalRecommendationsUseCase
import moe.rukamori.archivetune.home.recommendations.HomeSimilarRecommendationsUseCase
import moe.rukamori.archivetune.home.state.AccountChannelUiModel
import moe.rukamori.archivetune.home.state.AccountChannelsState
import moe.rukamori.archivetune.home.state.HomeStateComposer
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.pages.HomePage
import moe.rukamori.archivetune.models.SimilarRecommendation
import moe.rukamori.archivetune.utils.SavedAccount
import moe.rukamori.archivetune.utils.SyncUtils
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.reportException
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class HomeViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val syncUtils: SyncUtils,
    observeHomePresentationPreferences: ObserveHomePresentationPreferencesUseCase,
    private val quickPicksManager: HomeQuickPicksManager,
    private val localRecommendations: HomeLocalRecommendationsUseCase,
    private val similarRecommendationsUseCase: HomeSimilarRecommendationsUseCase,
    private val accountCoordinator: HomeAccountCoordinator,
    private val feedCoordinator: HomeFeedCoordinator,
    private val stateComposer: HomeStateComposer,
) : ViewModel() {
    constructor(
        context: Context,
        database: MusicDatabase,
        syncUtils: SyncUtils,
        switchSavedYouTubeAccount: SwitchSavedYouTubeAccountUseCase,
        observeHomePresentationPreferences: ObserveHomePresentationPreferencesUseCase,
        loadAiContentFilterPolicy: LoadAiContentFilterPolicyUseCase,
        filterAiContent: FilterAiContentUseCase,
        prepareCommunityHomePage: PrepareCommunityHomePageUseCase,
        loadCommunityPlaylistPreviews: LoadCommunityPlaylistPreviewsUseCase,
        filterCommunityPreviews: FilterCommunityPreviewsUseCase,
    ) : this(
        context = context,
        syncUtils = syncUtils,
        observeHomePresentationPreferences = observeHomePresentationPreferences,
        quickPicksManager = HomeQuickPicksManager(context, database),
        localRecommendations = HomeLocalRecommendationsUseCase(context, database),
        similarRecommendationsUseCase = HomeSimilarRecommendationsUseCase(context, database, loadAiContentFilterPolicy, filterAiContent),
        accountCoordinator = HomeAccountCoordinator(context, syncUtils, switchSavedYouTubeAccount),
        feedCoordinator = HomeFeedCoordinator(
            context = context,
            database = database,
            loadAiContentFilterPolicy = loadAiContentFilterPolicy,
            filterAiContent = filterAiContent,
            prepareCommunityHomePage = prepareCommunityHomePage,
            loadCommunityPlaylistPreviews = loadCommunityPlaylistPreviews,
            filterCommunityPreviews = filterCommunityPreviews,
        ),
        stateComposer = HomeStateComposer(),
    )

    private val isRefreshing = MutableStateFlow(false)
    private val isLoading = MutableStateFlow(false)
    private val isInitialLoadComplete = MutableStateFlow(false)
    private val loadError = MutableStateFlow<Int?>(null)
    private val loadMutex = Mutex()

    private val quickPicks = quickPicksManager.observe(viewModelScope)
    private val speedDialItems = MutableStateFlow<List<LocalItem>>(emptyList())
    private val forgottenFavorites = MutableStateFlow<List<Song>?>(null)
    private val keepListening = MutableStateFlow<List<LocalItem>?>(null)
    private val similarRecommendations = MutableStateFlow<List<SimilarRecommendation>?>(null)

    val communitySection: StateFlow<HomePage.Section?> = feedCoordinator.communitySection

    private val _allLocalItems = MutableStateFlow<List<LocalItem>>(emptyList())
    val allLocalItems: StateFlow<List<LocalItem>> = _allLocalItems.asStateFlow()
    private val _allYtItems = MutableStateFlow<List<YTItem>>(emptyList())
    val allYtItems: StateFlow<List<YTItem>> = _allYtItems.asStateFlow()

    val accountName: StateFlow<String> = accountCoordinator.accountName
    val accountImageUrl: StateFlow<String?> = accountCoordinator.accountImageUrl
    val accountChannelsState: StateFlow<AccountChannelsState> = accountCoordinator.accountChannelsState
    val accountAccentColor: StateFlow<Color?> = accountCoordinator.accountAccentColor

    private val presentationPreferences = observeHomePresentationPreferences()

    val screenState: StateFlow<HomeScreenState> =
        stateComposer.compose(
            scope = viewModelScope,
            quickPicks = quickPicks,
            speedDialItems = speedDialItems,
            forgottenFavorites = forgottenFavorites,
            keepListening = keepListening,
            homePage = feedCoordinator.homePage,
            similarRecommendations = similarRecommendations,
            accountPlaylists = accountCoordinator.accountPlaylists,
            accountName = accountCoordinator.accountName,
            accountImageUrl = accountCoordinator.accountImageUrl,
            communitySection = feedCoordinator.communitySection,
            selectedChip = feedCoordinator.selectedChip,
            presentationPreferences = presentationPreferences,
            isLoading = isLoading,
            isInitialLoadComplete = isInitialLoadComplete,
            loadError = loadError,
            isRefreshing = isRefreshing,
            isLoadingMore = feedCoordinator.isLoadingMore,
        )

    private fun updateAllLocalItems() {
        _allLocalItems.value =
            (quickPicks.value.orEmpty() + forgottenFavorites.value.orEmpty() + keepListening.value.orEmpty())
                .filter { it is Song || it is Album }
    }

    private fun updateAllYtItems() {
        val allItems: List<YTItem> =
            similarRecommendations.value?.flatMap { it.items }.orEmpty() +
                feedCoordinator.homePage.value
                    ?.sections
                    ?.flatMap { it.items }
                    .orEmpty() +
                feedCoordinator.communitySection.value?.items.orEmpty()
        _allYtItems.value = allItems
    }

    private suspend fun load() {
        delay(150.milliseconds)
        if (!loadMutex.tryLock()) return
        isLoading.value = true
        loadError.value = null

        try {
            supervisorScope {
                launch {
                    forgottenFavorites.value = localRecommendations.loadForgottenFavorites()
                }
                launch {
                    keepListening.value = localRecommendations.loadKeepListening()
                }
            }

            updateAllLocalItems()

            feedCoordinator.loadHome()
                .onSuccess { page ->
                    feedCoordinator.applyInitialHome(page)
                }.onFailure {
                    reportException(it)
                    loadError.value = R.string.error_unknown
                    feedCoordinator.onInitialHomeFailure()
                }

            viewModelScope.launch(Dispatchers.IO) {
                similarRecommendations.value = similarRecommendationsUseCase()
                updateAllYtItems()
            }

            updateAllYtItems()

            isInitialLoadComplete.value = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportException(e)
            loadError.value = R.string.error_unknown
        } finally {
            isInitialLoadComplete.value = true
            isLoading.value = false
            loadMutex.unlock()
        }
    }

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.Refresh -> refresh()
            is HomeAction.SelectChip -> feedCoordinator.toggleChip(action.chip)
            is HomeAction.LoadMore -> feedCoordinator.loadMoreItems(action.continuation)
        }
    }

    private fun refresh() {
        if (isRefreshing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            isRefreshing.value = true
            try {
                supervisorScope {
                    launch { load() }
                    launch {
                        quickPicksManager.refresh()
                        updateAllLocalItems()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
            } finally {
                isRefreshing.value = false
            }
        }
    }

    fun switchToAccount(
        account: SavedAccount,
        forceSyncOnSwitch: Boolean,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            accountCoordinator.switchAccount(account, forceSyncOnSwitch)
        }
    }

    fun switchToAccountChannel(
        channel: AccountChannelUiModel,
        forceSyncOnSwitch: Boolean,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            accountCoordinator.switchChannel(channel, forceSyncOnSwitch)
        }
    }

    init {
        feedCoordinator.bind(viewModelScope) {
            updateAllYtItems()
        }

        viewModelScope.launch(Dispatchers.IO) {
            quickPicks.collect {
                updateAllLocalItems()
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            load()
        }

        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.data
                .map { it[SpeedDialSongIdsKey].orEmpty() }
                .distinctUntilChanged()
                .collect {
                    speedDialItems.value = localRecommendations.loadSpeedDial()
                    updateAllLocalItems()
                }
        }

        viewModelScope.launch(Dispatchers.IO) {
            delay(3000.milliseconds)
            syncUtils.cleanupDuplicatePlaylists()
        }

        accountCoordinator.observeCookies(viewModelScope)
        accountCoordinator.observeAccentColor(viewModelScope)
    }
}
