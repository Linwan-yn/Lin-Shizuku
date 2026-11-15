package rikka.core.ktx

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

fun <T> unsafeLazy(initializer: () -> T): ReadOnlyProperty<Any?, T> =
    object : ReadOnlyProperty<Any?, T> {
        private var value: T? = null
        override fun getValue(thisRef: Any?, property: KProperty<*>): T {
            if (value == null) value = initializer()
            @Suppress("UNCHECKED_CAST")
            return value as T
        }
    }
