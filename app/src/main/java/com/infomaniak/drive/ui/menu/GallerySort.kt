/*
 * Infomaniak kDrive - Android
 * Copyright (C) 2026 Infomaniak Network SA
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

import androidx.annotation.StringRes
import com.infomaniak.drive.R
import com.infomaniak.drive.data.models.File
import com.infomaniak.drive.data.models.File.SortType
import java.util.Date

enum class GallerySort(@StringRes val translation: Int, val sortType: SortType) {
    DATE_TAKEN(R.string.gallerySortDateTaken, SortType.RECENT_CREATED),
    DATE_MODIFIED(R.string.gallerySortDateModified, SortType.RECENT),
    DATE_ADDED(R.string.gallerySortDateAdded, SortType.MOST_RECENT_ADDED);

    /** The date used to group [file] into sections, which must match the order the gallery is sorted by. */
    fun dateOf(file: File): Date = when (this) {
        // Not every uploader sends a creation date, so files without one fall back on their modification date
        DATE_TAKEN -> if (file.createdAt > 0) file.getFileCreatedAt() else file.getLastModifiedAt()
        DATE_MODIFIED -> file.getLastModifiedAt()
        DATE_ADDED -> file.getAddedAt()
    }
}
