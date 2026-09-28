package com.example.galleryai1

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * "재작년에", "지난 여름정도에" 같은 상대적 시간 표현을 실제 날짜 범위로 바꾼다.
 * 매칭된 시간 표현은 검색어에서 잘라내서 돌려준다 — 그대로 두면 "여름" 같은 단어가
 * 라벨/캡션 매칭에도 필수 조건으로 끼어들어서(어떤 AI 라벨도 "여름"이라고 말하진 않으니)
 * 검색 결과가 항상 0건이 되어버린다.
 *
 * 정교한 자연어 이해는 아니고, 자주 쓰이는 표현 위주로 규칙 기반 매칭한다.
 */
object DateExpressionParser {

    data class DateRange(val startMillis: Long, val endMillis: Long) {
        operator fun contains(millis: Long): Boolean = millis in startMillis..endMillis
    }

    data class ParseResult(val dateRange: DateRange?, val remainingQuery: String)

    private val zone: ZoneId = ZoneId.systemDefault()

    fun parse(query: String, today: LocalDate = LocalDate.now(zone)): ParseResult {
        for (rule in rules) {
            val match = rule.pattern.find(query) ?: continue
            val range = rule.toRange(match, today) ?: continue
            val remaining = query.replaceRange(match.range, " ").trim()
            return ParseResult(range, remaining)
        }
        return ParseResult(null, query)
    }

    private fun rangeOf(startInclusive: LocalDate, endExclusive: LocalDate): DateRange {
        val startMillis = startInclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return DateRange(startMillis, endMillis)
    }

    // 각 계절의 [시작, 끝) 범위. 겨울은 그 해 12월부터 다음 해 2월 말까지로 연도 경계를 넘는다.
    private fun seasonRange(year: Int, season: String): Pair<LocalDate, LocalDate> = when (season) {
        "봄" -> LocalDate.of(year, 3, 1) to LocalDate.of(year, 6, 1)
        "여름" -> LocalDate.of(year, 6, 1) to LocalDate.of(year, 9, 1)
        "가을" -> LocalDate.of(year, 9, 1) to LocalDate.of(year, 12, 1)
        else -> LocalDate.of(year, 12, 1) to LocalDate.of(year + 1, 3, 1)
    }

    // 두 자리로 줄여 쓴 연도("24년")를 네 자리 연도로 바꾼다. POSIX strptime의 %y 관례(00~68 →
    // 2000~2068, 69~99 → 1969~1999)를 그대로 따른다 — 사진 갤러리가 다루는 연도 범위(스마트폰
    // 대중화 이후~가까운 미래)엔 이 정도 관례면 충분하고, "오늘" 날짜에 의존하지 않아 결과가
    // 항상 같아서 테스트하기도 쉽다.
    private fun twoDigitYearToFull(twoDigit: Int): Int = if (twoDigit <= 68) 2000 + twoDigit else 1900 + twoDigit

    // 연도 지정 없이 "지난 여름"처럼 계절만 말했을 때: 이미 시작된(또는 진행 중인) 가장 최근
    // 그 계절을 찾는다. 아직 올해 그 계절이 시작 전이면 작년 것을 가리킨다.
    private fun mostRecentSeason(today: LocalDate, season: String): Pair<LocalDate, LocalDate> {
        val thisYear = seasonRange(today.year, season)
        return if (!today.isBefore(thisYear.first)) thisYear else seasonRange(today.year - 1, season)
    }

    private class Rule(val pattern: Regex, val toRange: (MatchResult, LocalDate) -> DateRange?)

    private const val SEASON = "(봄|여름|가을|겨울)"

    // 구체적인(연도+계절) 조합을 먼저 검사해야 "작년 여름"이 "작년" 단독 규칙에 선점당하지 않는다.
    private val rules = listOf(
        Rule(Regex("재작년\\s*$SEASON")) { m, today ->
            seasonRange(today.year - 2, m.groupValues[1]).let { (s, e) -> rangeOf(s, e) }
        },
        Rule(Regex("작년\\s*$SEASON")) { m, today ->
            seasonRange(today.year - 1, m.groupValues[1]).let { (s, e) -> rangeOf(s, e) }
        },
        Rule(Regex("올해\\s*$SEASON")) { m, today ->
            seasonRange(today.year, m.groupValues[1]).let { (s, e) -> rangeOf(s, e) }
        },
        Rule(Regex("지난\\s*$SEASON")) { m, today ->
            mostRecentSeason(today, m.groupValues[1]).let { (s, e) -> rangeOf(s, e) }
        },
        // "2024년 여름"처럼 절대 연도 + 계절. 연도 없는 계절 단독 규칙(바로 아래)보다
        // 먼저 검사해야 한다 — 안 그러면 "여름"만 걸려서 지정한 연도가 무시된다.
        Rule(Regex("(\\d{4})\\s*년\\s*$SEASON")) { m, _ ->
            m.groupValues[1].toIntOrNull()?.let { year ->
                seasonRange(year, m.groupValues[2]).let { (s, e) -> rangeOf(s, e) }
            }
        },
        // "24년 여름"처럼 두 자리로 줄여 쓴 연도 + 계절. 4자리 규칙(바로 위)이 먼저 시도되므로
        // "2024년 여름"의 뒤쪽 "24"만 잘못 떼어내 매칭되는 일은 없다.
        Rule(Regex("(\\d{2})\\s*년\\s*$SEASON")) { m, _ ->
            m.groupValues[1].toIntOrNull()?.let { twoDigit ->
                seasonRange(twoDigitYearToFull(twoDigit), m.groupValues[2]).let { (s, e) -> rangeOf(s, e) }
            }
        },
        Rule(Regex(SEASON)) { m, today ->
            mostRecentSeason(today, m.groupValues[1]).let { (s, e) -> rangeOf(s, e) }
        },
        // "2024년"처럼 절대 연도 단독. 상대 연도(작년/재작년/올해)나 "N년 전"과 달리
        // 이 표현은 기존 규칙 어디에도 안 걸려서, 이게 없으면 라벨 매칭으로 그대로 새서
        // (그 문자열이 AI 라벨에 있을 리 없으니) 검색이 항상 0건이고 불필요하게 느려진다.
        Rule(Regex("(\\d{4})\\s*년")) { m, _ ->
            m.groupValues[1].toIntOrNull()?.let { year ->
                rangeOf(LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1))
            }
        },
        Rule(Regex("(\\d+)\\s*년\\s*전")) { m, today ->
            m.groupValues[1].toIntOrNull()?.let { years ->
                rangeOf(LocalDate.of(today.year - years, 1, 1), LocalDate.of(today.year - years + 1, 1, 1))
            }
        },
        // "24년"처럼 두 자리로 줄여 쓴 절대 연도. "N년 전"(바로 위 규칙)이 먼저 시도되므로
        // "24년 전"(24년 전이라는 상대 표현)까지 절대 연도로 잘못 해석되는 일은 없다.
        Rule(Regex("(\\d{2})\\s*년")) { m, _ ->
            m.groupValues[1].toIntOrNull()?.let { twoDigit ->
                val year = twoDigitYearToFull(twoDigit)
                rangeOf(LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1))
            }
        },
        Rule(Regex("재작년")) { _, today ->
            rangeOf(LocalDate.of(today.year - 2, 1, 1), LocalDate.of(today.year - 1, 1, 1))
        },
        Rule(Regex("작년")) { _, today ->
            rangeOf(LocalDate.of(today.year - 1, 1, 1), LocalDate.of(today.year, 1, 1))
        },
        Rule(Regex("올해")) { _, today ->
            rangeOf(LocalDate.of(today.year, 1, 1), LocalDate.of(today.year + 1, 1, 1))
        },
        Rule(Regex("(지난|저번)\\s*달")) { _, today ->
            val d = today.minusMonths(1)
            val ym = YearMonth.of(d.year, d.month)
            rangeOf(ym.atDay(1), ym.plusMonths(1).atDay(1))
        },
        Rule(Regex("이번\\s*달")) { _, today ->
            val ym = YearMonth.of(today.year, today.month)
            rangeOf(ym.atDay(1), ym.plusMonths(1).atDay(1))
        },
        Rule(Regex("(지난|저번)\\s*주")) { _, today ->
            val startOfThisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val start = startOfThisWeek.minusWeeks(1)
            rangeOf(start, start.plusWeeks(1))
        },
        Rule(Regex("이번\\s*주")) { _, today ->
            val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            rangeOf(start, start.plusWeeks(1))
        },
        Rule(Regex("어제")) { _, today -> rangeOf(today.minusDays(1), today) },
        Rule(Regex("오늘")) { _, today -> rangeOf(today, today.plusDays(1)) },
        // 위 규칙들에서 아무것도 안 걸렸을 때만 "N월" 단독 표현을 시도한다(올해 기준).
        Rule(Regex("(\\d{1,2})\\s*월")) { m, today ->
            m.groupValues[1].toIntOrNull()?.takeIf { it in 1..12 }?.let { month ->
                val ym = YearMonth.of(today.year, month)
                rangeOf(ym.atDay(1), ym.plusMonths(1).atDay(1))
            }
        }
    )
}
