package com.example.galleryai1

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 사진 한 장에 대한 라벨(태그)을 보여주고 사용자 라벨을 추가/삭제하는 다이얼로그.
 * 그리드 롱프레스, 상세보기 메뉴("태그") 두 곳에서 공통으로 재사용한다.
 * AI(MobileCLIP)가 자동으로 붙인 태그는 상단에 별도로 표시만 하고(읽기 전용), 이 다이얼로그로는
 * 수정하지 않는다 — 사용자가 직접 추가한 태그만 여기서 관리한다.
 *
 * [TagRepository]는 Room을 쓰기 때문에 모든 조회/쓰기가 비동기(콜백 기반)다 — 예전 `LabelStore`
 * (SharedPreferences, 동기)와 달리 다이얼로그를 열자마자 라벨이 바로 채워지지 않고 살짝 늦게
 * 채워진다(로컬 DB라 체감상 거의 즉시).
 *
 * @param onLabelsChanged 다이얼로그가 닫힐 때(완료/뒤로가기/바깥 터치 포함) 최종 사용자 라벨 목록을 전달한다.
 */
class LabelEditDialog(
    private val context: Context,
    private val photoUri: String,
    private val onLabelsChanged: (List<String>) -> Unit
) {
    private lateinit var dialog: AlertDialog
    private lateinit var labelListContainer: LinearLayout
    private lateinit var tvNoLabels: TextView
    private val labels: MutableList<String> = mutableListOf()

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_edit_labels, null)
        labelListContainer = view.findViewById(R.id.labelListContainer)
        tvNoLabels = view.findViewById(R.id.tvNoLabels)
        val tvAiTagsStatus = view.findViewById<TextView>(R.id.tvAiTagsStatus)
        val etNewLabel = view.findViewById<EditText>(R.id.etNewLabel)
        val btnAddLabel = view.findViewById<TextView>(R.id.btnAddLabel)
        val btnClose = view.findViewById<TextView>(R.id.btnCloseLabelDialog)

        renderLabels()
        loadUserLabels()
        showAiTags(tvAiTagsStatus)

        btnAddLabel.setOnClickListener {
            val input = etNewLabel.text.toString().trim()
            when {
                input.isEmpty() -> Toast.makeText(context, "라벨을 입력해주세요.", Toast.LENGTH_SHORT).show()
                labels.contains(input) -> Toast.makeText(context, "이미 추가된 라벨이에요.", Toast.LENGTH_SHORT).show()
                else -> {
                    etNewLabel.text.clear()
                    TagRepository.addUserLabel(context, photoUri, input) { updatedLabels ->
                        labels.clear()
                        labels.addAll(updatedLabels)
                        renderLabels()
                    }
                }
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        dialog = AlertDialog.Builder(context)
            .setView(view)
            .create()
        dialog.setOnDismissListener { onLabelsChanged(labels) }
        dialog.show()
    }

    private fun loadUserLabels() {
        TagRepository.loadPhotoLabels(context, photoUri) { _, userLabels ->
            labels.clear()
            labels.addAll(userLabels)
            renderLabels()
        }
    }

    // AI 자동 태그 표시: 아직 분석 전이면(그리드 스크롤 중엔 MobileCLIP을 돌리지 않는다) 이 자리에서
    // 분석을 시작하고, 끝나는 대로 결과를 보여준다. 이미 분석됐으면 바로 조회만 한다.
    private fun showAiTags(tvAiTagsStatus: TextView) {
        tvAiTagsStatus.text = "🤖 AI 태그: 분석 중..."
        TagRepository.analyzePhoto(context, photoUri) {
            TagRepository.loadPhotoLabels(context, photoUri) { aiLabels, _ ->
                renderAiTags(tvAiTagsStatus, aiLabels)
            }
        }
    }

    private fun renderAiTags(tvAiTagsStatus: TextView, aiLabels: List<String>) {
        tvAiTagsStatus.text = if (aiLabels.isNotEmpty()) {
            "🤖 AI 태그: ${aiLabels.joinToString(", ")}"
        } else {
            "🤖 AI가 인식한 태그가 없어요"
        }
    }

    private fun renderLabels() {
        labelListContainer.removeAllViews()
        tvNoLabels.visibility = if (labels.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(context)
        labels.toList().forEach { label ->
            val row = inflater.inflate(R.layout.item_label_row, labelListContainer, false)
            row.findViewById<TextView>(R.id.tvLabelName).text = label
            row.findViewById<TextView>(R.id.btnDeleteLabel).setOnClickListener {
                TagRepository.removeUserLabel(context, photoUri, label) { updatedLabels ->
                    labels.clear()
                    labels.addAll(updatedLabels)
                    renderLabels()
                }
            }
            labelListContainer.addView(row)
        }
    }
}
