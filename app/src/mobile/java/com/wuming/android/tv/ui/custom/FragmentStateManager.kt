package com.wuming.android.tv.ui.custom

import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.wuming.android.tv.ui.base.BaseFragment

abstract class FragmentStateManager(
    private val container: ViewGroup,
    private val fm: FragmentManager,
) {
    abstract fun getItem(position: Int): Fragment

    open fun change(position: Int): Boolean {
        val tag = getTag(position)
        val fragment = fm.findFragmentByTag(tag) ?: getItem(position)
        val ft = fm.beginTransaction().setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
        if (fm.findFragmentByTag(tag) == null) {
            ft.add(container.id, fragment, tag)
        } else if (fragment.isDetached) {
            ft.attach(fragment)
        }
        val current = fm.primaryNavigationFragment
        if (current != null && current !== fragment) ft.hide(current)
        ft
            .show(fragment)
            .setPrimaryNavigationFragment(fragment)
            .setReorderingAllowed(true)
            .commitNowAllowingStateLoss()
        return true
    }

    /** Detach views before replacing the container, retaining fragment instances and state. */
    open fun detachAll() {
        val transaction = fm.beginTransaction().setReorderingAllowed(true)
        var changed = false
        for (position in 0 until 3) {
            val fragment = fm.findFragmentByTag(getTag(position)) ?: continue
            if (!fragment.isAdded || fragment.isDetached) continue
            transaction.detach(fragment)
            changed = true
        }
        if (!changed) return
        transaction.setPrimaryNavigationFragment(null).commitNowAllowingStateLoss()
    }

    private fun getTag(position: Int): String = "android:switcher:" + position

    open fun getFragment(position: Int): BaseFragment? = fm.findFragmentByTag(getTag(position)) as? BaseFragment

    open fun isVisible(position: Int): Boolean = getFragment(position)?.isVisible == true

    open fun canBack(position: Int): Boolean {
        val fragment = getFragment(position)
        return fragment != null &&
            (fragment.canBack() || fragment.isHidden)
    }
}
