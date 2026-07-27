package com.lezi.babylog.core.model

enum class RecordType(val key: String) {
    NURSING("nursing"),
    FORMULA("formula"),
    PUMPED_FEED("pumped_feed"),
    PUMP_EXPRESS("pump_express"),
    PEE("pee"),
    POOP("poop"),
    BOTH_DIAPER("both_diaper"),
    SLEEP("sleep"),
    TEMPERATURE("temperature"),
    DIARY("diary"),
    BATH("bath"),
    WALK("walk"),
    COUGH("cough"),
    RASH("rash"),
    VOMIT("vomit"),
    INJURY("injury"),
    MEDICINE("medicine"),
    HOSPITAL("hospital"),
    HEIGHT("height"),
    WEIGHT("weight"),
    BABY_FOOD("baby_food"),
    SNACK("snack"),
    DRINK("drink"),
    HEAD("head"),
    CHEST("chest"),
    FOOT_SIZE("foot_size"),
    VACCINE("vaccine"),
    CUSTOM("custom");

    companion object {
        fun fromKey(key: String): RecordType? = entries.find { it.key == key }
    }
}
