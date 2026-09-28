package com.example.galleryai1

/**
 * ML Kit 이미지 라벨링 모델은 영어 단어(Dog, Food, Sky 등)로 태그를 붙이기 때문에,
 * 한글로 검색해도 AI 태그와 매칭되도록 자주 쓰일 법한 한글 단어 ↔ 영어 단어를 매핑해 둔다.
 * 완벽한 사전은 아니고 흔한 사물/장면 위주로 구성했다 — 필요하면 계속 추가하면 된다.
 */
object SearchSynonyms {
    private val koreanToEnglish: Map<String, List<String>> = mapOf(
        // 동물
        "강아지" to listOf("dog", "puppy"),
        "개" to listOf("dog"),
        "고양이" to listOf("cat", "kitten"),
        "동물" to listOf("animal"),
        "새" to listOf("bird"),
        "물고기" to listOf("fish"),
        "곤충" to listOf("insect", "bug"),
        "나비" to listOf("butterfly"),
        "토끼" to listOf("rabbit"),
        "말" to listOf("horse"),
        "소" to listOf("cow"),
        "돼지" to listOf("pig"),
        "양" to listOf("sheep"),
        "닭" to listOf("chicken"),
        "돌고래" to listOf("dolphin"),
        "원숭이" to listOf("monkey"),
        "기린" to listOf("giraffe"),
        "미어캣" to listOf("meerkat"),
        "거북이" to listOf("turtle", "terrapin", "tortoise"),
        "거북" to listOf("turtle", "terrapin", "tortoise"),
        "곰" to listOf("bear"),
        "여우" to listOf("fox"),
        "부엉이" to listOf("owl"),
        "올빼미" to listOf("owl"),
        "앵무새" to listOf("parrot"),
        "동물원" to listOf("zoo", "safari"),
        "사파리" to listOf("safari"),
        "반려동물" to listOf("pet"),
        "애완동물" to listOf("pet"),
        "수중" to listOf("underwater"),
        "물속" to listOf("underwater"),

        // 사람
        "사람" to listOf("person", "people", "human"),
        "아기" to listOf("baby", "child"),
        "가족" to listOf("family", "people"),

        // 음식
        "음식" to listOf("food", "dish", "cuisine", "meal"),
        "과일" to listOf("fruit"),
        "채소" to listOf("vegetable"),
        "고기" to listOf("meat"),
        "빵" to listOf("bread"),
        "피자" to listOf("pizza"),
        "국수" to listOf("noodle", "noodles"),
        "밥" to listOf("rice"),
        "과자" to listOf("snack", "cookie"),
        "아이스크림" to listOf("ice cream"),
        "케이크" to listOf("cake", "dessert"),
        "음료" to listOf("drink", "beverage"),
        "커피" to listOf("coffee"),
        "맥주" to listOf("beer"),
        "와인" to listOf("wine"),
        "식기" to listOf("tableware"),
        "그릇" to listOf("tableware", "bowl"),
        "초밥" to listOf("sushi"),

        // 자연·풍경
        "바다" to listOf("sea", "ocean", "beach"),
        "산" to listOf("mountain"),
        "하늘" to listOf("sky"),
        "꽃" to listOf("flower", "plant"),
        "꽃잎" to listOf("petal"),
        "꽃밭" to listOf("flower", "field", "garden"),
        "들판" to listOf("field"),
        "바위" to listOf("rock"),
        "돌" to listOf("rock"),
        "나무" to listOf("tree", "plant"),
        "가지" to listOf("branch"),
        "눈" to listOf("snow"),
        "비" to listOf("rain"),
        "구름" to listOf("cloud"),
        "별" to listOf("star"),
        "달" to listOf("moon"),
        "물" to listOf("water"),
        "강" to listOf("river"),
        "호수" to listOf("lake"),
        "석양" to listOf("sunset"),
        "일몰" to listOf("sunset"),
        "일출" to listOf("sunrise"),
        "불" to listOf("fire"),
        "밤" to listOf("night"),
        "낮" to listOf("day"),
        "실내" to listOf("indoor"),
        "실외" to listOf("outdoor"),

        // 사물
        "책" to listOf("book"),
        "컴퓨터" to listOf("computer", "laptop"),
        "휴대폰" to listOf("phone", "smartphone", "mobile phone"),
        "의자" to listOf("chair", "furniture"),
        "테이블" to listOf("table", "furniture"),
        "신발" to listOf("shoe", "footwear"),
        "옷" to listOf("clothing", "clothes"),
        "청바지" to listOf("jeans"),
        "재킷" to listOf("jacket"),
        "자켓" to listOf("jacket"),
        "외투" to listOf("outerwear", "jacket"),
        "군복" to listOf("military uniform"),
        "유니폼" to listOf("uniform", "military uniform"),
        "제복" to listOf("uniform", "military uniform"),
        "모자" to listOf("hat"),
        "안경" to listOf("glasses", "eyewear"),
        "시계" to listOf("clock", "watch"),
        "악기" to listOf("musical instrument", "guitar", "piano"),
        "음악" to listOf("music"),
        "금속" to listOf("metal"),
        "무늬" to listOf("pattern"),
        "패턴" to listOf("pattern"),

        // 사물 (2026-09-14: YOLO11n이 아는 COCO 80종 중 사전에 아예 없던 46개 개념을 추가함.
        // [SearchSynonyms]/`seed_tags.json`/`YoloEngine.COCO_TO_TAG_EN` 세 곳을 항상 같이 맞춰야
        // 한다 — 여기 없으면 한글 검색이 안 걸리고, seed_tags.json에 없으면 MobileCLIP 제로샷
        // 태깅 후보 자체가 안 생기고, COCO_TO_TAG_EN에 없으면 YOLO가 탐지해도 태그로 안 이어진다.)
        "트럭" to listOf("truck"),
        "벤치" to listOf("bench"),
        "코끼리" to listOf("elephant"),
        "얼룩말" to listOf("zebra"),
        "백팩" to listOf("backpack"),
        "배낭" to listOf("backpack"),
        "우산" to listOf("umbrella"),
        "핸드백" to listOf("handbag"),
        "넥타이" to listOf("tie"),
        "캐리어" to listOf("suitcase"),
        "여행가방" to listOf("suitcase"),
        "프리스비" to listOf("frisbee"),
        "원반" to listOf("frisbee"),
        "스노보드" to listOf("snowboard"),
        "연" to listOf("kite"),
        "스케이트보드" to listOf("skateboard"),
        "서핑보드" to listOf("surfboard"),
        "테니스라켓" to listOf("tennis racket"),
        "병" to listOf("bottle"),
        "와인잔" to listOf("wine glass"),
        "컵" to listOf("cup"),
        "포크" to listOf("fork"),
        "칼" to listOf("knife"),
        "숟가락" to listOf("spoon"),
        "바나나" to listOf("banana"),
        "사과" to listOf("apple"),
        "샌드위치" to listOf("sandwich"),
        "오렌지" to listOf("orange"),
        "핫도그" to listOf("hot dog"),
        "도넛" to listOf("donut"),
        "소파" to listOf("couch"),
        "화분" to listOf("potted plant"),
        "침대" to listOf("bed"),
        "변기" to listOf("toilet"),
        "텔레비전" to listOf("tv"),
        "티비" to listOf("tv"),
        "마우스" to listOf("mouse"),
        "리모컨" to listOf("remote"),
        "키보드" to listOf("keyboard"),
        "전자레인지" to listOf("microwave"),
        "오븐" to listOf("oven"),
        "싱크대" to listOf("sink"),
        "냉장고" to listOf("refrigerator"),
        "꽃병" to listOf("vase"),
        "가위" to listOf("scissors"),
        "곰인형" to listOf("teddy bear"),
        "드라이기" to listOf("hair drier"),
        "칫솔" to listOf("toothbrush"),

        // 학업/행사 (2026-09-07: 실기기 실사용 중 "한능검 책", "해커톤 명찰", "단체 셀카" 사진에
        // 대응하는 개념이 사전에 아예 없어서 MobileCLIP 제로샷이 근거 없이 헤매던 걸 확인하고 추가함
        // — [[galleryai1-s24-realdevice-session]] 참고. 임계값 튜닝으로는 "사전에 없는 개념"
        // 자체는 못 고치므로, 실제로 자주 나올 법한 개념을 보강하는 쪽으로 대응.)
        "명찰" to listOf("name tag", "badge"),
        "행사" to listOf("event"),
        "회의" to listOf("meeting"),
        "발표" to listOf("presentation"),
        "공부" to listOf("studying"),
        "시험" to listOf("exam"),
        "노트북" to listOf("laptop"),
        "단체사진" to listOf("group photo"),
        "학교" to listOf("school"),
        "강의실" to listOf("classroom"),
        "세미나" to listOf("seminar"),
        "회의실" to listOf("conference room"),

        // 이동수단
        "자동차" to listOf("car", "vehicle"),
        "자전거" to listOf("bicycle", "bike"),
        "비행기" to listOf("airplane", "aircraft"),
        "기차" to listOf("train"),
        "버스" to listOf("bus"),
        "배" to listOf("boat", "ship"),
        "오토바이" to listOf("motorcycle"),

        // 장소·건축
        "길" to listOf("road", "street"),
        "다리" to listOf("bridge"),
        "건물" to listOf("building", "architecture"),
        "벽" to listOf("wall"),
        "타워" to listOf("tower"),
        "공원" to listOf("park"),
        "놀이터" to listOf("playground"),
        "식당" to listOf("restaurant"),
        "레스토랑" to listOf("restaurant"),
        "카페" to listOf("cafe", "coffee shop"),

        // 행사·활동
        "크리스마스" to listOf("christmas"),
        "생일" to listOf("birthday"),
        "파티" to listOf("party"),
        "결혼식" to listOf("wedding"),
        "불꽃놀이" to listOf("fireworks"),
        "운동" to listOf("sport", "sports"),
        "축구" to listOf("soccer", "football"),
        "농구" to listOf("basketball"),
        "야구" to listOf("baseball"),

        // 상태·느낌
        "재미" to listOf("fun"),
        "즐거움" to listOf("fun", "leisure"),
        "여가" to listOf("leisure"),
        "휴식" to listOf("leisure"),
        "서있는" to listOf("standing"),
        "서 있는" to listOf("standing"),
        "멋진" to listOf("cool")
    )

    // 조사가 붙은 채로 검색해도 매칭되도록("바다에서" -> "바다") 흔한 조사를 떼어낸다.
    // 길이가 긴 조사부터 검사해야 "으로"를 "로"로 잘못 덜 떼어내는 일이 없다.
    private val trailingParticles = listOf(
        "에서", "으로서", "으로써", "으로", "로써", "로서", "에게서", "한테서", "이랑", "이나",
        "이든", "이라도", "까지", "부터", "밖에", "만큼", "처럼", "마다",
        "에게", "한테", "은", "는", "이", "가", "을", "를", "의", "도", "만", "에", "로", "와", "과", "랑", "나", "든"
    )

    // 자연어 문장 속에서 의미 없이 아무 데나 걸리는 것을 막기 위해 제외하는 영어 불용어.
    private val englishStopWords = setOf(
        "a", "an", "the", "of", "in", "on", "at", "to", "is", "are", "was", "were",
        "for", "and", "or", "my", "me", "it", "this", "that"
    )

    // 검색 문장에서 실제 "찾을 대상"이 아닌 군더더기 말들 — 개념 그룹으로 뽑히면 안 된다.
    // (안 걸러내면 "사진 보여줘"의 "사진"이 그 자체로 필수 조건이 되어버려서, 어떤 라벨에도
    // "사진"이라는 단어가 없으니 결과가 항상 0건이 된다)
    private val searchStopWords = setOf(
        "사진", "이미지", "찾아줘", "찾아주세요", "보여줘", "보여주세요", "알려줘", "알려주세요",
        "좀", "정도", "쯤", "경", "것", "거", "건", "때",
        "찍은", "찍힌", "찍었던", "있는", "있던", "있었던", "나온", "나오는", "보이는", "포함된"
    )

    private fun stripTrailingParticle(token: String): String {
        val particle = trailingParticles
            .filter { token.length > it.length && token.endsWith(it) }
            .maxByOrNull { it.length }
        return if (particle != null) token.dropLast(particle.length) else token
    }

    // koreanToEnglish 사전을 뒤져서, 주어진 단어 변형(원형/조사 뗀 형태 등) 중 하나라도 걸리는
    // 항목의 대응어(한글→영어, 영어→한글)를 모아 돌려준다. expand()/expandGrouped()가 공유한다.
    // internal: AiPromptTagger가 AI로 뽑아낸 개념에 사전 동의어를 보강할 때도 재사용한다.
    internal fun matchDictionaryTerms(variants: Set<String>): Set<String> {
        val terms = mutableSetOf<String>()
        koreanToEnglish.forEach { (korean, englishList) ->
            val koreanHit = if (korean.length <= 1) {
                variants.contains(korean)
            } else {
                variants.any { it.contains(korean) || (it.length >= 2 && korean.contains(it)) }
            }
            if (koreanHit) {
                terms.addAll(englishList)
            }
            if (englishList.any { eng -> variants.any { it == eng || it.contains(eng) } }) {
                terms.add(korean)
            }
        }
        return terms
    }

    /**
     * 검색어를 실제 매칭에 쓸 키워드 집합으로 확장한다.
     * - 문장을 단어 단위로 쪼개서 각 단어 자체도 검색어로 인정한다
     *   (문장 전체를 통째로만 비교하면 "cute cat photo"처럼 여러 단어일 때 전혀 매칭되지 않는다)
     * - 한글 단어면 대응하는 영어 단어들을 추가한다 (AI 태그가 영어라서)
     * - 영어 단어면 대응하는 한글 단어를 추가한다 (사용자가 한글 라벨을 붙였을 수 있어서)
     * - 사전 키가 한 글자([산],[물] 등)인 경우는 부분 일치 대신 단어 단위 완전 일치만 인정한다.
     *   그렇지 않으면 "부산"의 "산", "선물"의 "물"처럼 무관한 단어 속 음절과 오매칭된다.
     *
     * 검색어에 포함된 단어 중 하나만 걸려도 통과되는 OR 방식이다 — 여러 사물이 결합된 문장은
     * [expandGrouped]를 쓴다.
     */
    fun expand(query: String): Set<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptySet()

        val rawTokens = q.split(Regex("[\\s,.!?~]+")).filter { it.isNotEmpty() }
        val tokenVariants = (rawTokens + rawTokens.map { stripTrailingParticle(it) }).toSet()

        val terms = mutableSetOf(q)
        terms.addAll(rawTokens.filter { it.length >= 2 && it !in englishStopWords })
        terms.addAll(matchDictionaryTerms(tokenVariants))

        return terms
    }

    // 조사를 뗀 뒤 불용어/어간 조각을 걸러내고 남는 "진짜 찾을 대상" 토큰들.
    // expandGrouped()와 hasMeaningfulContent()가 공유하고, TranslationTagger도 번역할 단어를
    // 고를 때 같은 기준을 쓰려고 재사용한다("사진", "찾아줘"까지 번역기에 보낼 필요는 없다).
    internal fun extractMeaningfulTokens(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        val rawTokens = q.split(Regex("[\\s,.!?~]+")).filter { it.isNotEmpty() }
        return rawTokens.mapNotNull { raw ->
            if (raw in searchStopWords) return@mapNotNull null
            val stripped = stripTrailingParticle(raw)
            if (stripped.isEmpty() || stripped in searchStopWords || stripped in trailingParticles) {
                return@mapNotNull null
            }
            // "노는"(놀다) → "는" 조사를 떼면 "노" 한 글자만 남는데, 이건 명사가 아니라 동사
            // 활용형의 어간 조각이다. 사전에 등록된 진짜 한 글자 단어("눈", "산" 등)가 아니면
            // 걸러낸다 — 안 그러면 어떤 라벨에도 없는 조각이 필수 조건이 되어 결과가 항상 0건이 된다.
            if (stripped.length == 1 && stripped !in koreanToEnglish) {
                return@mapNotNull null
            }
            stripped
        }
    }

    /**
     * 검색어에 실제로 "찾을 대상"이 되는 단어가 하나라도 있는지를 판단한다.
     * "2024년 사진 찾아줘"에서 날짜 표현("2024년")을 걷어내고 남은 "사진 찾아줘"처럼
     * 불용어뿐인 텍스트면 false — 이미 날짜만으로 결과가 정해진 검색에서 이 텍스트를
     * 별도 라벨 조건으로 취급하면 안 된다(MainActivity가 날짜 전용 검색을 판별할 때 쓴다).
     */
    fun hasMeaningfulContent(query: String): Boolean = extractMeaningfulTokens(query).isNotEmpty()

    /**
     * 문장을 "명사 개념" 단위로 나눠서, 개념마다 동의어 확장 집합(그룹)을 돌려준다.
     * 사진은 반환된 그룹 **전부**에서 최소 하나씩은 걸려야 매칭된 것으로 본다
     * (그룹 간 AND, 같은 그룹 안 동의어끼리는 OR).
     *
     * 예) "아기가 꽃밭에 있는 사진" -> [{아기, baby, child}, {꽃밭, flower, field, ...}]
     * "아기"만 있고 "꽃밭"은 없는 사진은 이제 안 걸린다 — 기존 expand()의 OR 매칭은 "아기"
     * 하나만 걸려도 통과시켜서 여러 사물이 결합된 문장에서 정확도가 크게 떨어졌었다.
     */
    fun expandGrouped(query: String): List<Set<String>> {
        if (query.trim().isEmpty()) return emptyList()

        val meaningfulTokens = extractMeaningfulTokens(query)
        if (meaningfulTokens.isEmpty()) {
            // 의미 있는 단어를 하나도 못 골라내면(예: "사진 보여줘"만 입력) 기존 방식대로
            // 원문 전체를 한 그룹으로 취급해서 최소한의 매칭이라도 시도한다.
            return listOf(expand(query))
        }

        return meaningfulTokens.map { token -> setOf(token) + matchDictionaryTerms(setOf(token)) }
    }
}
