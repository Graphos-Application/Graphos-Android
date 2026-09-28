package com.example.galleryai1

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class GalleryAdapter(private var items: List<GalleryItem>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PHOTO = 1
    }

    /**
     * 검색 결과 등으로 표시할 목록을 통째로 교체한다.
     * notifyDataSetChanged() 대신 DiffUtil로 실제 바뀐 항목만 갱신해서,
     * 그대로 남아있는 썸네일까지 Glide로 다시 로드되며 깜빡이는 걸 막는다.
     */
    fun updateItems(newItems: List<GalleryItem>) {
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val old = items[oldPos]
                val new = newItems[newPos]
                return when {
                    old is GalleryItem.Photo && new is GalleryItem.Photo -> old.uri == new.uri
                    old is GalleryItem.Header && new is GalleryItem.Header -> old.date == new.date
                    else -> false
                }
            }

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                return items[oldPos] == newItems[newPos]
            }
        })
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position] is GalleryItem.Header) TYPE_HEADER else TYPE_PHOTO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false)
            PhotoViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is HeaderViewHolder && item is GalleryItem.Header) {
            holder.tvTitle.text = item.date
            holder.tvSubTitle.text = item.location
        } else if (holder is PhotoViewHolder && item is GalleryItem.Photo) {
            val context = holder.itemView.context
            val photoUriString = item.uri.toString()

            // Glide 라이브러리를 사용해 동적으로 이미지 로드
            Glide.with(context)
                .load(item.uri)
                .centerCrop()
                .into(holder.ivPhoto)

            // MobileCLIP은 예전 AutoTagger(가벼운 ML Kit 기본 라벨링)보다 무거워서 그리드 스크롤
            // 중엔 자동 태깅하지 않는다 — 라벨 편집 다이얼로그를 열거나 검색할 때만 분석한다
            // (PreciseTagger/FaceTagger가 쓰던 "무거운 모델은 지연 실행" 원칙과 동일).

            //클릭 이벤트 : 상세보기로 이동
            holder.itemView.setOnClickListener {
                val intent = android.content.Intent(context, DetailActivity::class.java)
                // 선택한 사진의 URI 주소를 DetailActivity로 전달
                val photoUris = items.filterIsInstance<GalleryItem.Photo>().map { it.uri.toString() }
                val currentIndex = photoUris.indexOf(photoUriString)

                intent.putStringArrayListExtra("photo_uris", ArrayList(photoUris))
                intent.putExtra("current_index", currentIndex)
                context.startActivity(intent)
            }

            // 롱프레스 : 라벨 편집 다이얼로그
            holder.itemView.setOnLongClickListener {
                LabelEditDialog(context, photoUriString) {}.show()
                true
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvHeaderTitle)
        val tvSubTitle: TextView = view.findViewById(R.id.tvHeaderSubTitle)
    }

    class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivPhoto: ImageView = view.findViewById(R.id.ivPhoto)
    }
}