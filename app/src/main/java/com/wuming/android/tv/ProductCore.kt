package com.wuming.android.tv

/** Shared product queries, registered by the flavor-specific product implementation. */
abstract class ProductCore {
    protected abstract fun deviceTypeImpl(): Int

    protected abstract fun nameTextSizeImpl(): Int

    protected abstract fun badgeTextSizeImpl(): Int

    companion object {
        @Volatile private var instance: ProductCore? = null

        @JvmStatic fun setInstance(instance: ProductCore?) {
            this.instance = instance
        }

        @JvmStatic fun getDeviceType(): Int = instance?.deviceTypeImpl() ?: 1

        @JvmStatic fun getNameTextSize(): Int = instance?.nameTextSizeImpl() ?: 24

        @JvmStatic fun getBadgeTextSize(): Int = instance?.badgeTextSizeImpl() ?: 16

        @JvmStatic fun isTv(): Boolean = getDeviceType() == 0

        @JvmStatic fun isMobile(): Boolean = getDeviceType() == 1
    }
}
