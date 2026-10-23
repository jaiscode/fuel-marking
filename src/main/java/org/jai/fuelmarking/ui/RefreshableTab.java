package org.jai.fuelmarking.ui;

import javafx.scene.control.Tab;

abstract class RefreshableTab extends Tab {
    RefreshableTab(String title) {
        super(title);
        setClosable(false);
    }

    private String group;

    /** Puts this screen under a named menu in the sidebar (e.g. "Jerricans"); screens without a group are listed on their own. */
    RefreshableTab inGroup(String name) { this.group = name; return this; }

    String groupName() { return group; }

    /** Reload data from the local DB; called whenever the tab is selected. */
    abstract void refresh();
}
