/*
 * Infomaniak kDrive - Android
 * Copyright (C) 2022-2026 Infomaniak Network SA
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.infomaniak.drive.ui.menu

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.infomaniak.drive.data.api.ApiRepository
import com.infomaniak.drive.data.api.CursorApiResponse
import com.infomaniak.drive.data.cache.FileController
import com.infomaniak.drive.data.models.File
import com.infomaniak.drive.data.models.UiSettings
import com.infomaniak.drive.utils.IsComplete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import splitties.init.appCtx

class GalleryViewModel : ViewModel() {

    private val uiSettings = UiSettings(appCtx)

    val period = MutableStateFlow(GalleryPeriod.MONTH)

    private val _sort = MutableStateFlow(uiSettings.gallerySort)
    val sort: StateFlow<GallerySort> = _sort.asStateFlow()

    private var getGalleryJob: Job? = null

    private var currentCursor: String? = null

    private var lastGalleryFiles = arrayListOf<File>()

    val galleryApiResult = MutableLiveData<Pair<ArrayList<File>, IsComplete>>()
    val needToRestoreFiles get() = galleryApiResult.isInitialized

    fun loadLastGallery(driveId: Int, ignoreCloud: Boolean) {
        lastGalleryFiles = arrayListOf()
        loadLastGallery(driveId, ignoreCloud, isFirstPage = true)
    }

    fun loadMoreGallery(driveId: Int, ignoreCloud: Boolean) {
        currentCursor?.let {
            loadLastGallery(driveId, ignoreCloud, isFirstPage = false, currentCursor)
        }
    }

    fun setSort(newSort: GallerySort) {
        uiSettings.gallerySort = newSort
        _sort.value = newSort
    }

    fun restoreGalleryFiles() {
        if (needToRestoreFiles) {
            val isComplete = galleryApiResult.value?.second ?: true
            galleryApiResult.value = lastGalleryFiles to isComplete
        }
    }

    private fun loadLastGallery(
        driveId: Int,
        ignoreCloud: Boolean,
        isFirstPage: Boolean,
        cursor: String? = null,
    ) {
        getGalleryJob?.cancel()
        val requestedSort = sort.value
        getGalleryJob = viewModelScope.launch(Dispatchers.IO) {
            val result = getLastGallery(driveId, ignoreCloud, isFirstPage, cursor, requestedSort) ?: return@launch
            galleryApiResult.postValue(result)
            lastGalleryFiles.addAll(result.first)
        }
    }

    private fun getLastGallery(
        driveId: Int,
        ignoreCloud: Boolean,
        isFirstPage: Boolean,
        cursor: String?,
        sort: GallerySort,
    ): Pair<ArrayList<File>, IsComplete>? {
        getGalleryJob?.cancel()
        getGalleryJob = Job()

        return if (ignoreCloud) emitRealmGallery(sort) else fetchApiGallery(driveId, isFirstPage, cursor, sort)
    }

    private fun emitRealmGallery(sort: GallerySort): Pair<ArrayList<File>, Boolean> {
        currentCursor = null
        // The cached gallery keeps the order of the last API call, which may have used another sort
        val files = FileController.getGalleryDrive().sortedByDescending(sort::dateOf)
        return ArrayList(files) to true
    }

    /** Returns null when the sort changed while the request was running, as its result would be out of order. */
    private fun fetchApiGallery(
        driveId: Int,
        isFirstPage: Boolean,
        cursor: String?,
        requestedSort: GallerySort,
    ): Pair<ArrayList<File>, Boolean>? {
        val apiResponse = ApiRepository.getLastGallery(driveId = driveId, sortType = requestedSort.sortType, cursor = cursor)
        if (requestedSort != sort.value) return null

        return if (apiResponse.isSuccess()) {
            currentCursor = apiResponse.cursor
            emitApiGallery(apiResponse, isFirstPage)
        } else {
            emitRealmGallery(requestedSort)
        }
    }

    private fun emitApiGallery(
        apiResponse: CursorApiResponse<ArrayList<File>>,
        isFirstPage: Boolean,
    ): Pair<ArrayList<File>, Boolean> {
        val data = apiResponse.data

        val results = if (data.isNullOrEmpty()) {
            arrayListOf<File>() to true
        } else {
            FileController.storeGalleryDrive(data, isFirstPage)
            val isComplete = !apiResponse.hasMore
            data to isComplete
        }

        if (isFirstPage) FileController.removeOrphanFiles()
        return results
    }

    override fun onCleared() {
        getGalleryJob?.cancel()
        super.onCleared()
    }
}
