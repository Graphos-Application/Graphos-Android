package com.example.galleryai1

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        // searchWithMobileClip()이 태깅 진행 중 화면을 갱신하는 최소 간격 — 자세한 이유는 그 함수 참고.
        private const val RESULT_RENDER_THROTTLE_MS = 250L
    }

    private lateinit var recyclerView: RecyclerView
    private var galleryAdapter: GalleryAdapter? = null
    private val galleryItems = mutableListOf<GalleryItem>()

    private lateinit var headerNormalRow: View
    private lateinit var headerSearchRow: View
    private lateinit var etSearchQuery: EditText
    private lateinit var progressSearch: ProgressBar
    private lateinit var tvNoResults: TextView

    // MediaStore 조회 + 역지오코딩(Geocoder, 네트워크 호출) + MobileCLIP 추론/Room DB 접근은
    // 메인 스레드에서 돌리면 화면이 멈추거나(ANR) Room이 예외를 던지므로 별도 스레드에서 처리한다.
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // onCreate 직후 곧바로 첫 onResume이 따라오는데, 그건 checkGalleryPermissions()가
    // 이미 처리한 로딩과 중복이라 건너뛰기 위한 플래그.
    private var isFirstResume = true

    // 검색은 태깅·번역 등 여러 비동기 작업이 얽혀서 진행된다. 사용자가 검색을 취소하거나
    // 다른 검색어로 다시 검색하면, 이전 검색이 나중에 도착해서 화면을 엉뚱하게 덮어쓸 수 있다.
    // 검색을 새로 시작하거나 취소할 때마다 값을 올려서, 그 시점 이후 도착하는 이전 세대는
    // 화면을 건드리지 않고 조용히 무시하게 한다.
    private var searchGeneration = 0

    // 1. 다중 권한 요청 (사진 접근 + 사진 위치 정보 접근)
    @RequiresApi(Build.VERSION_CODES.Q)
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readImageGranted = permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: false
        if (readImageGranted) {
            loadLocalPhotos()
        } else {
            Toast.makeText(this, "갤러리 접근 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 전체화면 (몰입형) 모드 적용
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContentView(R.layout.activity_main)
        recyclerView = findViewById(R.id.recyclerViewGallery)

        headerNormalRow = findViewById(R.id.headerNormalRow)
        headerSearchRow = findViewById(R.id.headerSearchRow)
        etSearchQuery = findViewById(R.id.etSearchQuery)
        progressSearch = findViewById(R.id.progressSearch)
        tvNoResults = findViewById(R.id.tvNoResults)
        setupSearchUi()

        checkGalleryPermissions()
    }

    private fun setupSearchUi() {
        findViewById<View>(R.id.btnAiSearch).setOnClickListener { enterSearchMode() }
        findViewById<View>(R.id.btnCloseSearch).setOnClickListener { exitSearchMode() }

        etSearchQuery.setOnEditorActionListener { _, actionId, event ->
            val isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isSearchAction) {
                performSearch(etSearchQuery.text.toString())
                true
            } else {
                false
            }
        }
    }

    private fun enterSearchMode() {
        headerNormalRow.visibility = View.GONE
        headerSearchRow.visibility = View.VISIBLE
        etSearchQuery.setText("")
        etSearchQuery.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etSearchQuery, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun exitSearchMode() {
        // 진행 중이던 검색을 낡은 세대로 만든다 — 뒤늦게 도착하는 그 검색의 콜백들이 아래에서
        // 다시 보여주는 전체 목록을 덮어쓰지 못하게 막는다.
        searchGeneration++
        headerSearchRow.visibility = View.GONE
        headerNormalRow.visibility = View.VISIBLE
        etSearchQuery.setText("")
        progressSearch.visibility = View.GONE
        tvNoResults.visibility = View.GONE

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearchQuery.windowToken, 0)

        galleryAdapter?.updateItems(galleryItems)
        recyclerView.scrollToPosition(0)
    }

    // MobileCLIP 태그(자동 태깅 + 사용자 태그)로 사진을 찾는다. 한글 검색어 <-> 영어 태그가 서로
    // 매칭되도록 SearchSynonyms/TranslationTagger로 검색어를 확장한다. 아직 태깅 전인 사진은
    // 검색 시점에 바로 분석부터 마치고 결과에 반영한다.
    private fun performSearch(rawQuery: String) {
        // 이 검색을 새 세대로 등록한다 — 이전 검색에서 아직 안 끝난 작업이 있다면 이제 전부
        // 낡은 세대가 되어, 화면에 도달해도 무시된다.
        val myGeneration = ++searchGeneration

        if (rawQuery.trim().isEmpty()) {
            tvNoResults.visibility = View.GONE
            galleryAdapter?.updateItems(galleryItems)
            return
        }

        var allPhotos = galleryItems.filterIsInstance<GalleryItem.Photo>()
        if (allPhotos.isEmpty()) {
            tvNoResults.visibility = View.VISIBLE
            galleryAdapter?.updateItems(emptyList())
            return
        }

        // "재작년에", "지난 여름정도에" 같은 시간 표현을 먼저 걷어내서 날짜 범위로 거른다.
        // 남는 텍스트("아들과 식당에서")만 태그 매칭에 넘긴다 — 그대로 두면 "여름" 같은 단어가
        // 태그 매칭에도 필수 조건으로 끼어들어서 결과가 항상 0건이 되어버린다.
        val (dateRange, remainingQuery) = DateExpressionParser.parse(rawQuery)
        if (dateRange != null) {
            allPhotos = allPhotos.filter { it.dateTakenMillis in dateRange }
            if (allPhotos.isEmpty()) {
                tvNoResults.visibility = View.VISIBLE
                galleryAdapter?.updateItems(emptyList())
                return
            }
            // "2024년 사진 찾아줘"처럼 날짜 표현을 걷어내고 남은 텍스트가 "사진"/"찾아줘" 같은
            // 불용어뿐이면(실제 찾을 대상이 없으면) 그 텍스트를 태그 조건으로 취급하지 않는다.
            if (!SearchSynonyms.hasMeaningfulContent(remainingQuery)) {
                tvNoResults.visibility = View.GONE
                galleryAdapter?.updateItems(allPhotos)
                recyclerView.scrollToPosition(0)
                return
            }
        }

        tvNoResults.visibility = View.GONE
        progressSearch.visibility = View.VISIBLE

        // 번역 기반 동의어 확장(모든 기기에서 동작)을 먼저 시도하고, 안 되면(모델 다운로드 전 등)
        // searchWithMobileClip 안에서 SearchSynonyms 사전 기반으로 대체한다 — "되면 보강, 안 되면
        // 조용히 기존 방식" 원칙은 그대로 유지.
        TranslationTagger.expandToConceptGroups(this, remainingQuery) { translationConceptGroups ->
            if (myGeneration != searchGeneration) return@expandToConceptGroups
            searchWithMobileClip(remainingQuery, allPhotos, translationConceptGroups, myGeneration)
        }
    }

    // Tier1(태그 인덱스 매칭)을 우선 시도하고, 걸리는 게 하나도 없으면 Tier2(MobileCLIP 벡터 유사도
    // 폴백)로 넘어간다 — setting.md 검색 파이프라인 4.2절. 사진이 많으면 이미 태깅된 사진부터
    // 먼저 보여주고 나머지는 태깅이 끝나는 대로 결과를 갱신한다.
    private fun searchWithMobileClip(
        rawQuery: String,
        allPhotos: List<GalleryItem.Photo>,
        translationConceptGroups: List<Set<String>>?,
        generation: Int
    ) {
        backgroundExecutor.execute {
            TagRepository.registerPhotos(this, allPhotos)
            if (generation != searchGeneration) return@execute

            val conceptGroups = translationConceptGroups ?: SearchSynonyms.expandGrouped(rawQuery)
            if (conceptGroups.isEmpty()) {
                mainHandler.post {
                    if (generation != searchGeneration) return@post
                    progressSearch.visibility = View.GONE
                    tvNoResults.visibility = if (allPhotos.isEmpty()) View.VISIBLE else View.GONE
                    galleryAdapter?.updateItems(allPhotos)
                    recyclerView.scrollToPosition(0)
                }
                return@execute
            }

            val pendingUris = allPhotos.map { it.uri.toString() }.filterNot { TagRepository.isAnalyzed(this, it) }

            fun renderResults(stillWorking: Boolean) {
                if (generation != searchGeneration) return
                val tier1 = TagRepository.findMatchingUris(this, conceptGroups)
                // 아직 태깅 중인 사진이 있으면 Tier1이 비어도 성급하게 Tier2로 넘어가지 않는다
                // (전부 끝난 뒤에야 "정말 태그로는 못 찾음"을 확정할 수 있다).
                val finalUris = if (tier1.isNotEmpty() || stillWorking) tier1 else TagRepository.vectorSearch(this, rawQuery).toSet()
                val results = allPhotos.filter { finalUris.contains(it.uri.toString()) }
                mainHandler.post {
                    if (generation != searchGeneration) return@post
                    galleryAdapter?.updateItems(results)
                    if (!stillWorking) {
                        progressSearch.visibility = View.GONE
                        tvNoResults.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                        recyclerView.scrollToPosition(0)
                    }
                }
            }

            // renderResults()는 매번 Tier1 DB 조회(+ 필요시 전체 사진 스캔하는 Tier2)를 다시 돌리므로,
            // 태깅 안 된 사진이 많을 때(수백 장) 사진 한 장 끝날 때마다 매번 부르면 그만큼 불필요한
            // DB 왕복이 쌓여 전체 검색이 오히려 느려진다. 일정 간격으로만 갱신하고, 처음(즉시 응답
            // 체감용)과 마지막(최종 결과 확정)은 항상 갱신한다.
            var lastRenderElapsedMs = 0L
            renderResults(stillWorking = pendingUris.isNotEmpty())
            pendingUris.forEachIndexed { index, uri ->
                if (generation != searchGeneration) return@execute
                TagRepository.analyzePhotoBlocking(this, uri)
                val isLast = index == pendingUris.lastIndex
                val now = SystemClock.elapsedRealtime()
                if (shouldRenderSearchProgress(isLast, now, lastRenderElapsedMs, RESULT_RENDER_THROTTLE_MS)) {
                    lastRenderElapsedMs = now
                    renderResults(stillWorking = !isLast)
                }
            }
        }
    }

    private fun hasGalleryPermission(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun checkGalleryPermissions() {
        if (hasGalleryPermission()) {
            loadLocalPhotos()
        } else {
            requestPermissionsLauncher.launch(
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION)
            )
        }
    }

    // 2. 사진의 실제 위치 정보를 가져와 주소(시/구)로 변환하는 함수
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getLocationName(uri: Uri): String {
        try {
            // 안드로이드 10 이상: 위치 정보가 포함된 원본 사진을 시스템에 요청
            val originalUri = MediaStore.setRequireOriginal(uri)
            contentResolver.openInputStream(originalUri)?.use { stream ->
                val exif = ExifInterface(stream)

                // --- 에러가 났던 부분 수정 ---
                val latLong = FloatArray(2) // 값을 담을 빈 배열 생성
                if (exif.getLatLong(latLong)) { // 위치 정보가 있으면 배열에 값을 채우고 true 반환
                    val lat = latLong[0].toDouble()
                    val lng = latLong[1].toDouble()

                    val geocoder = Geocoder(this, Locale.KOREAN)

                    // 위도/경도를 주소로 변환
                    val addresses = geocoder.getFromLocation(lat, lng, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        // locality(시), subLocality(구), adminArea(도) 중 있는 것을 반환
                        return address.locality ?: address.subLocality ?: address.adminArea ?: "위치 정보 없음"
                    }
                }
                // -----------------------------
            }
        } catch (e: Exception) {
            // 위치 정보가 아예 없는 사진이거나, 권한이 거부된 경우 무시
        }
        return "기기 저장 사진" // 기본값
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun loadLocalPhotos() {
        backgroundExecutor.execute {
            val items = queryPhotosWithHeaders()
            mainHandler.post {
                galleryItems.clear()
                galleryItems.addAll(items)
                setupRecyclerView()
            }

            // 지금 사진첩에 실제로 존재하는 사진을 Room에 등록하고, 삭제된 사진의 태깅 데이터를 정리한다.
            val photos = items.filterIsInstance<GalleryItem.Photo>()
            TagRepository.registerPhotos(this, photos)
            TagRepository.pruneDeleted(this, photos.map { it.uri.toString() }.toSet())
        }
    }

    // MediaStore 커서 순회 + 날짜별 역지오코딩을 백그라운드 스레드에서 수행하고 결과 리스트만 반환한다.
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun queryPhotosWithHeaders(): List<GalleryItem> {
        val result = mutableListOf<GalleryItem>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

            val dateFormat = SimpleDateFormat("M월 d일", Locale.KOREAN)
            var lastDate = ""

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateTaken = cursor.getLong(dateColumn)
                val contentUri: Uri = ContentUris.withAppendedId(uri, id)

                val photoDate = dateFormat.format(Date(dateTaken))

                // 날짜가 바뀔 때마다 해당 일자의 첫 번째 사진 위치를 읽어옵니다.
                if (photoDate != lastDate) {
                    lastDate = photoDate
                    val locationName = getLocationName(contentUri)
                    result.add(GalleryItem.Header(photoDate, locationName))
                }

                result.add(GalleryItem.Photo(contentUri, dateTaken))
            }
        }
        return result
    }

    private fun setupRecyclerView() {
        val adapter = GalleryAdapter(galleryItems)
        galleryAdapter = adapter
        val gridLayoutManager = GridLayoutManager(this, 4)

        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == 0) 4 else 1
            }
        }

        recyclerView.layoutManager = gridLayoutManager
        recyclerView.adapter = adapter
    }

    // 기획서 "1-A) 어플을 키거나 ... 추가 및 삭제된 사진에 대해 태깅작업 실시"에 대응:
    // 앱을 백그라운드로 보낸 사이(다른 갤러리 앱 등에서) 사진이 삭제됐을 수도 있으므로,
    // 다시 포그라운드로 돌아올 때마다 사진첩을 재조회해서 추가/삭제를 다시 반영한다.
    // (onCreate 직후 첫 onResume은 checkGalleryPermissions()가 이미 로딩했으니 건너뜀)
    //
    // 주의: 상세보기(DetailActivity)에서 뒤로 나올 때도 onResume이 매번 불린다. 처음엔
    // loadLocalPhotos()(= setupRecyclerView() 재호출)를 그대로 썼는데, 그러면 매번 새
    // GridLayoutManager가 만들어져서 스크롤이 맨 위로 튀고, 마침 검색 결과를 보고 있던 중이면
    // 그 위에 전체 사진 목록으로 어댑터가 통째로 교체돼버려서 검색 결과가 사라지거나 뒤죽박죽되는
    // 회귀 버그가 있었다. 그래서 여기서는 setupRecyclerView()를 다시 부르지 않고, ① 태깅 데이터
    // 정리는 항상 조용히 하되 ② 화면 갱신은 검색 중이 아닐 때만, 그것도 DiffUtil 기반
    // updateItems()로 스크롤 위치를 유지한 채 반영한다.
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onResume() {
        super.onResume()
        if (isFirstResume) {
            isFirstResume = false
            return
        }
        if (!hasGalleryPermission()) return

        backgroundExecutor.execute {
            val items = queryPhotosWithHeaders()
            val photos = items.filterIsInstance<GalleryItem.Photo>()
            TagRepository.registerPhotos(this, photos)
            TagRepository.pruneDeleted(this, photos.map { it.uri.toString() }.toSet())

            mainHandler.post {
                // 검색 결과를 보여주는 중이면 건드리지 않는다 — 위 설명대로 여기서 화면을
                // 갈아치우면 진행 중이거나 방금 끝난 검색 결과와 충돌한다.
                if (headerSearchRow.visibility != View.VISIBLE) {
                    galleryItems.clear()
                    galleryItems.addAll(items)
                    if (galleryAdapter != null) {
                        galleryAdapter?.updateItems(galleryItems)
                    } else {
                        setupRecyclerView()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
    }
}

/**
 * [MainActivity.searchWithMobileClip]가 태깅 진행 중 화면을 얼마나 자주 갱신할지 결정하는 순수 로직.
 * 마지막 사진이거나 마지막 갱신 이후 [throttleMs]가 지났으면 갱신한다 — 그 외엔 건너뛰어서, 사진이
 * 많을 때 사진 한 장 끝날 때마다 Tier1 DB 재조회가 쌓이는 걸 막는다.
 */
internal fun shouldRenderSearchProgress(isLast: Boolean, nowMs: Long, lastRenderMs: Long, throttleMs: Long): Boolean =
    isLast || nowMs - lastRenderMs >= throttleMs
