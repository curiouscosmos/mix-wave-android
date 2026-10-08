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
import org.videolan.vlc.R
import org.videolan.vlc.discourse.Discourse
import org.videolan.vlc.gui.BaseFragment

class HomeFragment : BaseFragment() {
    override fun getTitle() = getString(R.string.home)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.source_browser, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<ViewPager2>(R.id.pager).apply {
            adapter = object : FragmentStateAdapter(this@HomeFragment) {
                override fun getItemCount() = 1
                override fun createFragment(position: Int) = RecentlyPlayedDiscoursesFragment()
            }
            isUserInputEnabled = false
        }
    }

    fun openDiscourse(discourse: Discourse) {
        DiscourseDetailActivity.open(requireContext(), discourse)
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu) = false
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
    override fun onDestroyActionMode(mode: ActionMode) = Unit
}
