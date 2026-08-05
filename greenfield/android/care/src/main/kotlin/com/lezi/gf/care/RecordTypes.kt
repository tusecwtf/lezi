package com.lezi.gf.care

/**
 * Built-in record types. English keys are wire/storage only — UI uses [chineseLabel].
 */
enum class RecordType(val key: String, val chineseLabel: String) {
    NURSING("nursing", "喂奶"),
    FORMULA("formula", "配方奶"),
    PUMPED_FEED("pumped_feed", "瓶喂母乳"),
    PUMP_EXPRESS("pump_express", "吸奶"),
    PEE("pee", "尿"),
    POOP("poop", "便"),
    BOTH_DIAPER("both_diaper", "尿+便"),
    SLEEP("sleep", "睡眠"),
    TEMPERATURE("temperature", "体温"),
    DIARY("diary", "日记"),
    BATH("bath", "洗澡"),
    WALK("walk", "散步"),
    COUGH("cough", "咳嗽"),
    RASH("rash", "皮疹"),
    VOMIT("vomit", "呕吐"),
    INJURY("injury", "外伤"),
    MEDICINE("medicine", "用药"),
    HOSPITAL("hospital", "就医"),
    HEIGHT("height", "身高"),
    WEIGHT("weight", "体重"),
    BABY_FOOD("baby_food", "辅食"),
    SNACK("snack", "零食"),
    DRINK("drink", "饮水"),
    HEAD_SIZE("head_size", "头围"),
    CHEST_SIZE("chest_size", "胸围"),
    FOOT_SIZE("foot_size", "脚长"),
    VACCINE("vaccine", "疫苗"),
    CUSTOM("custom", "自定义"),
    ;

    companion object {
        fun fromKey(key: String): RecordType? = entries.find { it.key == key }
        fun allBuiltin(): List<RecordType> = entries.filter { it != CUSTOM }
    }
}

/** Default amount step for formula / pumped feed / pump express (ml). */
const val DEFAULT_AMOUNT_STEP_ML: Int = 5

/**
 * Default open amount for milk-type composers (ml).
 * Aligns with 0.3.x formula Composer default (~120ml) for dual-install UX parity.
 */
const val DEFAULT_MILK_AMOUNT_ML: Int = 120
