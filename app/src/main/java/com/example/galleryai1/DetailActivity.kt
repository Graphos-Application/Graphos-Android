package com.example.galleryai1

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.viewpager2.widget.ViewPager2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContentView(R.layout.activity_detail)

        val viewPager = findViewById<ViewPager2>(R.id.viewPagerDetail)
        val btnBack = findViewById<ImageView>(R.id.btnBack)
        val btnMoreMenu = findViewById<ImageView>(R.id.btnMoreMenu)

        btnBack.setOnClickListener { finish() }


        // MainActivity에서 넘겨준 전체 사진 리스트와 인덱스를 받습니다.
        val photoUris = intent.getStringArrayListExtra("photo_uris") ?: return
        var currentIndex = intent.getIntExtra("current_index", 0)

        val adapter = DetailPagerAdapter(photoUris)
        viewPager.adapter = adapter
        viewPager.setCurrentItem(currentIndex, false)

        // 우측 상단 메뉴 버튼: "상세정보 보기" / "태그" 두 가지를 제공한다
        btnMoreMenu.setOnClickListener {
            val popup = PopupMenu(this, btnMoreMenu)
            popup.menuInflater.inflate(R.menu.detail_menu, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.menu_view_info -> {
                        showPhotoInfoDialog(photoUris[currentIndex])
                        true
                    }
                    R.id.menu_edit_tags -> {
                        LabelEditDialog(this, photoUris[currentIndex]) {}.show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentIndex = position
            }
        })
    }

    // "상세정보 보기" 메뉴 선택 시 날짜/해상도/용량을 별도 창(다이얼로그)으로 보여준다
    private fun showPhotoInfoDialog(photoUri: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_photo_info, null)
        val tvInfoDate = view.findViewById<TextView>(R.id.tvInfoDate)
        val tvInfoSize = view.findViewById<TextView>(R.id.tvInfoSize)
        val btnClose = view.findViewById<TextView>(R.id.btnCloseInfoDialog)

        loadPhotoDetails(Uri.parse(photoUri), tvInfoDate, tvInfoSize)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun loadPhotoDetails(uri: Uri, tvDate: TextView, tvSize: TextView) {
        val projection = arrayOf(
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE
        )

        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val dateTaken = cursor.getLong(0)
                val width = cursor.getInt(1)
                val height = cursor.getInt(2)
                val sizeBytes = cursor.getLong(3)

                if (dateTaken > 0) {
                    val dateFormat = SimpleDateFormat("yyyy년 M월 d일 HH:mm", Locale.KOREAN)
                    tvDate.text = dateFormat.format(Date(dateTaken))
                } else {
                    tvDate.text = "날짜 정보 없음"
                }

                var detailText = ""
                if (width > 0 && height > 0) {
                    detailText += "${width}x${height}  ·  "
                }
                if (sizeBytes > 0) {
                    val sizeMb = sizeBytes / (1024.0 * 1024.0)
                    detailText += String.format("%.2f MB", sizeMb)
                }
                tvSize.text = detailText.ifEmpty { "상세 정보 없음" }
            }
        }
    }
}