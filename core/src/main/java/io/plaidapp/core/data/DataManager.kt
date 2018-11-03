/*
 *   Copyright 2018 Google LLC
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */

package io.plaidapp.core.data

import io.plaidapp.core.data.prefs.SourceManager
import io.plaidapp.core.designernews.domain.LoadStoriesUseCase
import io.plaidapp.core.designernews.domain.SearchStoriesUseCase
import io.plaidapp.core.dribbble.data.ShotsRepository
import io.plaidapp.core.dribbble.data.api.model.Shot
import io.plaidapp.core.producthunt.data.api.provideProductHuntRepository
import io.plaidapp.core.ui.FilterAdapter
import retrofit2.Call
import java.util.HashMap

/**
 * Responsible for loading data from the various sources. Instantiating classes are responsible for
 * providing the [onDataLoaded] function to do something with the data.
 */
class DataManager(
        onDataLoadedCallback: BaseDataManager.OnDataLoadedCallback<List<PlaidItem>>,
        private val loadStoriesUseCase: LoadStoriesUseCase,
        private val searchStoriesUseCase: SearchStoriesUseCase,
        private val shotsRepository: ShotsRepository,
        private val filterAdapter: FilterAdapter
) : BaseDataManager<List<PlaidItem>>(), LoadSourceCallback {
    private val productHuntRepository = provideProductHuntRepository()
    private var pageIndexes = mutableMapOf<String, Int>()
    private var inflightCalls = mutableMapOf<String, Call<*>>()

    private val filterListener = object : FilterAdapter.FiltersChangedCallbacks() {
        override fun onFiltersChanged(changedFilter: Source) {
            if (changedFilter.active) {
                loadSource(changedFilter)
            } else {
                // filter deactivated
                val key = changedFilter.key
                inflightCalls.remove(key)?.let(Call<*>::cancel)
                loadStoriesUseCase.cancelRequestOfSource(key)
                searchStoriesUseCase.cancelRequestOfSource(key)
                // clear the page index for the source
                pageIndexes[key] = 0
            }
        }
    }

    init {
        setOnDataLoadedCallback(onDataLoadedCallback)
        filterAdapter.registerFilterChangedCallback(filterListener)
        setupPageIndexes()
    }

    fun loadAllDataSources() {
        for (filter in filterAdapter.filters) {
            loadSource(filter)
        }
    }

    override fun cancelLoading() {
        if (inflightCalls.isNotEmpty()) {
            for (call in inflightCalls.values) {
                call.cancel()
            }
            inflightCalls.clear()
        }
        shotsRepository.cancelAllSearches()
        loadStoriesUseCase.cancelAllRequests()
        searchStoriesUseCase.cancelAllRequests()
        productHuntRepository.cancelAllRequests()
    }

    internal fun loadSource(source: Source) {
        if (source.active) {
            loadStarted()
            val page = getNextPageIndex(source.key)
            when (source.key) {
                SourceManager.SOURCE_DESIGNER_NEWS_POPULAR -> loadDesignerNewsStories(page)
                SourceManager.SOURCE_PRODUCT_HUNT -> loadProductHunt(page)
                else -> if (source is Source.DribbbleSearchSource) {
                    loadDribbbleSearch(source, page)
                } else if (source is Source.DesignerNewsSearchSource) {
                    loadDesignerNewsSearch(source, page)
                }
            }
        }
    }

    private fun setupPageIndexes() {
        val dateSources = filterAdapter.filters
        pageIndexes = HashMap(dateSources.size)
        for (source in dateSources) {
            pageIndexes[source.key] = 0
        }
    }

    private fun getNextPageIndex(dataSource: String): Int {
        var nextPage = 1 // default to one – i.e. for newly added sources
        if (dataSource in pageIndexes) {
            nextPage = pageIndexes[dataSource]!! + 1
        }
        pageIndexes[dataSource] = nextPage
        return nextPage
    }

    private fun sourceIsEnabled(key: String): Boolean {
        return pageIndexes[key] != 0
    }

    override fun sourceLoaded(result: List<PlaidItem>?, page: Int, source: String) {
        loadFinished()
        if (result != null && !result.isEmpty() && sourceIsEnabled(source)) {
            BaseDataManager.setPage(result, page)
            BaseDataManager.setDataSource(result, source)
            onDataLoaded(result)
        }
        inflightCalls.remove(source)
    }

    override fun loadFailed(source: String) {
        loadFinished()
        inflightCalls.remove(source)
    }

    private fun loadDesignerNewsStories(page: Int) {
        loadStoriesUseCase.invoke(page, this)
    }

    private fun loadDesignerNewsSearch(source: Source.DesignerNewsSearchSource, page: Int) {
        searchStoriesUseCase.invoke(source.key, page, this)
    }

    private fun loadDribbbleSearch(source: Source.DribbbleSearchSource, page: Int) {
        shotsRepository.search(source.query, page) { result ->
            if (result is Result.Success<*>) {
                val (data) = result as Result.Success<List<Shot>>
                sourceLoaded(data, page, source.key)
            } else {
                loadFailed(source.key)
            }
        }
    }

    private fun loadProductHunt(page: Int) {
        // this API's paging is 0 based but this class (& sorting) is 1 based so adjust locally
        productHuntRepository.loadProductHuntData(
                page - 1,
                { it ->
                    sourceLoaded(it, page, SourceManager.SOURCE_PRODUCT_HUNT)
                },
                {
                    loadFailed(SourceManager.SOURCE_PRODUCT_HUNT)
                })
    }
}
