package com.lezi.babylog.feature.log.composer
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

/**
 * Local, non-medical complementary-feeding tips keyed by chronological month age.
 * Content is intentionally short and conservative; always show a disclaimer in UI.
 */
data class BabyFoodStage(
    val id: String,
    /** Inclusive lower bound in completed months. */
    val minMonthInclusive: Int,
    /** Exclusive upper bound; null means open-ended. */
    val maxMonthExclusive: Int?,
    val title: String,
    val explanation: String,
    val suggestions: List<String>,
)

data class BabyFoodGuidance(
    val monthAge: Int,
    val ageLabel: String,
    val stage: BabyFoodStage,
)

internal val BABY_FOOD_STAGES: List<BabyFoodStage> = listOf(
    BabyFoodStage(
        id = "pre6",
        minMonthInclusive = 0,
        maxMonthExclusive = 6,
        title = "6 月龄前 · 以奶为主",
        explanation = "多数公共营养指南建议满约 6 月龄再引入辅食，此前以母乳或配方奶满足营养。" +
            "个别宝宝可按儿保医生评估提前或延后。若已开始尝试，请每次只加一种新食物，并观察皮肤、大便与精神状态。",
        suggestions = emptyList(),
    ),
    BabyFoodStage(
        id = "m6_7",
        minMonthInclusive = 6,
        maxMonthExclusive = 8,
        title = "6–7 月龄 · 泥糊起步",
        explanation = "此阶段可从每天 1 次、少量泥糊开始，优先富铁食物（如高铁米粉），再逐步尝试单一蔬果泥。" +
            "新食物建议间隔数天再换下一种，便于观察耐受。奶量仍是主食，辅食是补充。",
        suggestions = listOf("高铁米粉", "南瓜泥", "西葫芦泥", "土豆泥", "苹果泥（蒸）", "胡萝卜泥"),
    ),
    BabyFoodStage(
        id = "m8_9",
        minMonthInclusive = 8,
        maxMonthExclusive = 10,
        title = "8–9 月龄 · 增加性状与蛋白",
        explanation = "可把泥糊调稠，加入碎菜、蛋黄与肉泥等优质蛋白，并继续保证铁的摄入。" +
            "一次仍以少量尝试为主，避免整粒坚果、整颗葡萄等易噎食物。",
        suggestions = listOf("稠粥", "蛋黄", "肉泥", "碎菜粥", "豆腐泥", "香蕉泥"),
    ),
    BabyFoodStage(
        id = "m10_11",
        minMonthInclusive = 10,
        maxMonthExclusive = 12,
        title = "10–11 月龄 · 小块与手指食物",
        explanation = "可过渡到软饭、小块蔬菜与适合抓握的手指食物，练习咀嚼与手部协调。" +
            "继续多样化，注意切小、煮软；家长陪同进食，警惕呛噎。",
        suggestions = listOf("软饭", "小块蔬菜", "手指食物", "碎肉末", "果泥", "鸡蛋羹"),
    ),
    BabyFoodStage(
        id = "m12_plus",
        minMonthInclusive = 12,
        maxMonthExclusive = null,
        title = "12 月龄及以上 · 家庭餐软食化",
        explanation = "可逐步靠拢家庭餐，把菜肴切小煮软，减少额外盐糖；奶类仍可保留。" +
            "继续鼓励自主进食，留意食物多样性与过敏史。",
        suggestions = listOf("家庭餐软食", "软面条", "小馄饨", "碎菜肉末", "手指点心", "继续奶类"),
    ),
)

internal const val BABY_FOOD_DISCLAIMER =
    "仅供参考，不构成医疗或营养诊疗建议。请结合宝宝发育与儿保/儿科指导调整。"

/**
 * Completed calendar months between birthday and [atMillis] (local date).
 * Before birthday returns 0.
 */
internal fun monthAgeAt(
    birthdayEpochDay: Long,
    atMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Int {
    val birth = LocalDate.ofEpochDay(birthdayEpochDay)
    val day = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()
    if (day.isBefore(birth)) return 0
    return Period.between(birth, day).toTotalMonths().toInt().coerceAtLeast(0)
}

internal fun formatMonthAgeLabel(monthAge: Int): String {
    return when {
        monthAge <= 0 -> "未满 1 月龄"
        monthAge >= 36 -> "约 ${monthAge / 12} 岁${monthAge % 12} 月龄"
        else -> "约 $monthAge 月龄"
    }
}

internal fun resolveBabyFoodStage(monthAge: Int): BabyFoodStage {
    val age = monthAge.coerceAtLeast(0)
    return BABY_FOOD_STAGES.firstOrNull { stage ->
        age >= stage.minMonthInclusive &&
            (stage.maxMonthExclusive == null || age < stage.maxMonthExclusive)
    } ?: BABY_FOOD_STAGES.last()
}

internal fun babyFoodGuidanceAt(
    birthdayEpochDay: Long,
    atMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): BabyFoodGuidance {
    val monthAge = monthAgeAt(birthdayEpochDay, atMillis, zone)
    val stage = resolveBabyFoodStage(monthAge)
    return BabyFoodGuidance(
        monthAge = monthAge,
        ageLabel = formatMonthAgeLabel(monthAge),
        stage = stage,
    )
}
