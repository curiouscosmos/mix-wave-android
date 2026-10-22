package org.videolan.vlc.gui

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ActionMode
import org.videolan.vlc.R

class DisclaimerFragment : BaseFragment() {
    override fun getTitle() = getString(R.string.acknowledgements_disclaimer)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.disclaimer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.disclaimer_acknowledgements).movementMethod = LinkMovementMethod.getInstance()
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
    override fun onDestroyActionMode(mode: ActionMode?) {}
}
