/*
 * DPP - Serious Distributed Pair Programming
 * (c) Freie Universitaet Berlin - Fachbereich Mathematik und Informatik - 2006
 * (c) Riad Djemili - 2006
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 1, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package de.fu_berlin.inf.dpp.ui.wizards;

import java.util.LinkedList;
import java.util.List;

import org.eclipse.jface.wizard.Wizard;
import org.picocontainer.annotations.Inject;

import de.fu_berlin.inf.dpp.Saros;

/**
 * A wizard to configure Saros.
 * 
 */
public class ConfigurationWizard extends Wizard {

    @Inject
    Saros saros;

    public ConfigurationWizard() {
        setWindowTitle("Saros Configuration");
        setHelpAvailable(false);
        setNeedsProgressMonitor(true);

        Saros.reinject(this);
        this.wizards.add(new RegisterAccountPage(saros, false, false, true));
        this.wizards.add(new NetworkSettingsPage(saros));
    }

    protected List<IWizardPage2> wizards = new LinkedList<IWizardPage2>();

    @Override
    public void addPages() {
        for (IWizardPage2 wizard : this.wizards) {
            addPage(wizard);
        }
    }

    @Override
    public boolean performFinish() {

        for (IWizardPage2 wizard : this.wizards) {
            if (!wizard.performFinish()) {
                getContainer().showPage(wizard);
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean performCancel() {
        return true;
    }
}