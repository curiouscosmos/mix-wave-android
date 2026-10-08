package org.videolan.vlc.gui.audio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ActionMode
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.R
import org.videolan.vlc.gui.BaseFragment
import org.videolan.vlc.interfaces.Filterable
import org.videolan.vlc.util.findCurrentFragment

class DiscoursesFragment : BaseFragment(), TabLayout.OnTabSelectedListener, Filterable {
    override val hasTabs = true
    private var tabLayout: TabLayout? = null
    private lateinit var viewPager: ViewPager2
    private val settings by lazy(LazyThreadSafetyMode.NONE) { Settings.getInstance(requireContext()) }
    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (position in 0 until TAB_COUNT) settings.putSingle(KEY_DISCOURSES_TAB, position)
        }
    }

    override fun getTitle() = getString(R.string.discourses)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.source_browser, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tabLayout = requireActivity().findViewById(R.id.sliding_tabs)
        viewPager = view.findViewById(R.id.pager)
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = TAB_COUNT
            override fun createFragment(position: Int) = when (position) {
                DISCOURSE_TAB -> DiscourseFragment()
                LOCAL_TAB -> AudioBrowserFragment()
                else -> DiscourseFragment()
            }
        }
        viewPager.isUserInputEnabled = false
        viewPager.registerOnPageChangeCallback(pageChangeCallback)
        viewPager.setCurrentItem(settings.getInt(KEY_DISCOURSES_TAB, DISCOURSE_TAB).coerceIn(0, TAB_COUNT - 1), false)
    }

    override fun onStart() {
        super.onStart()
        tabLayout?.apply {
            removeAllTabs()
            addTab(newTab().setText(R.string.discourse))
            addTab(newTab().setText(R.string.local))
            addOnTabSelectedListener(this@DiscoursesFragment)
            selectTab(getTabAt(viewPager.currentItem))
        }
    }

    override fun onStop() {
        tabLayout?.removeOnTabSelectedListener(this)
        currentFragment<BaseFragment>()?.stopActionMode()
        super.onStop()
    }

    override fun onDestroyView() {
        viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroyView()
    }

    override fun onTabSelected(tab: TabLayout.Tab) {
        viewPager.setCurrentItem(tab.position, false)
        activity?.invalidateOptionsMenu()
    }

    override fun onTabUnselected(tab: TabLayout.Tab) = currentFragment<BaseFragment>()?.stopActionMode() ?: Unit
    override fun onTabReselected(tab: TabLayout.Tab) = Unit

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.ml_menu_filter)?.isVisible = viewPager.currentItem == LOCAL_TAB
    }

    private inline fun <reified T> currentFragment() =
        viewPager.findCurrentFragment(childFragmentManager) as? T

    override fun onCreateActionMode(mode: ActionMode, menu: Menu) = false
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
    override fun onDestroyActionMode(mode: ActionMode) = Unit

    override fun getFilterQuery() = currentFragment<Filterable>()?.getFilterQuery()
    override fun enableSearchOption() = currentFragment<Filterable>()?.enableSearchOption() == true
    override fun filter(query: String) = currentFragment<Filterable>()?.filter(query) ?: Unit
    override fun restoreList() = currentFragment<Filterable>()?.restoreList() ?: Unit
    override fun setSearchVisibility(visible: Boolean) = currentFragment<Filterable>()?.setSearchVisibility(visible) ?: Unit
    override fun allowedToExpand() = currentFragment<Filterable>()?.allowedToExpand() == true

    private companion object {
        const val DISCOURSE_TAB = 0
        const val LOCAL_TAB = 1
        const val TAB_COUNT = 2
        const val KEY_DISCOURSES_TAB = "osho_discourses_current_tab"
    }
}
