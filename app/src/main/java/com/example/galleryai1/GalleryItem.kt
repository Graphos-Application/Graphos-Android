package com.example.galleryai1

import android.net.Uri

sealed class GalleryItem{
    data class Header(val date: String, val location: String) : GalleryItem()
    // dateTakenMillis: "재작년에", "지난 여름" 같은 시간 표현 검색(DateExpressionParser)에 쓴다.
    data class Photo(val uri: Uri, val dateTakenMillis: Long) : GalleryItem()
}
