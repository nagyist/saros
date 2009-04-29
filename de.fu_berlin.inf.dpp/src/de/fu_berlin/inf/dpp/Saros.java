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
package de.fu_berlin.inf.dpp;

import java.awt.Toolkit;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.Roster;
import org.jivesoftware.smack.RosterEntry;
import org.jivesoftware.smack.SASLAuthentication;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.Roster.SubscriptionMode;
import org.jivesoftware.smackx.ServiceDiscoveryManager;
import org.jivesoftware.smackx.packet.Jingle;
import org.osgi.framework.BundleContext;
import org.picocontainer.MutablePicoContainer;
import org.picocontainer.PicoBuilder;
import org.picocontainer.PicoCompositionException;
import org.picocontainer.injectors.AnnotatedFieldInjection;
import org.picocontainer.injectors.CompositeInjection;
import org.picocontainer.injectors.ConstructorInjection;
import org.picocontainer.injectors.Reinjector;

import de.fu_berlin.inf.dpp.concurrent.watchdog.ConsistencyWatchdogClient;
import de.fu_berlin.inf.dpp.concurrent.watchdog.ConsistencyWatchdogServer;
import de.fu_berlin.inf.dpp.concurrent.watchdog.IsInconsistentObservable;
import de.fu_berlin.inf.dpp.concurrent.watchdog.SessionViewOpener;
import de.fu_berlin.inf.dpp.editor.EditorManager;
import de.fu_berlin.inf.dpp.editor.internal.EditorAPI;
import de.fu_berlin.inf.dpp.net.IConnectionListener;
import de.fu_berlin.inf.dpp.net.JID;
import de.fu_berlin.inf.dpp.net.business.CancelInviteHandler;
import de.fu_berlin.inf.dpp.net.business.ChecksumHandler;
import de.fu_berlin.inf.dpp.net.business.ConsistencyWatchdogHandler;
import de.fu_berlin.inf.dpp.net.business.InvitationHandler;
import de.fu_berlin.inf.dpp.net.business.LeaveHandler;
import de.fu_berlin.inf.dpp.net.business.RequestForActivityHandler;
import de.fu_berlin.inf.dpp.net.business.UserListHandler;
import de.fu_berlin.inf.dpp.net.internal.DataTransferManager;
import de.fu_berlin.inf.dpp.net.internal.MultiUserChatManager;
import de.fu_berlin.inf.dpp.net.internal.SubscriptionListener;
import de.fu_berlin.inf.dpp.net.internal.XMPPChatReceiver;
import de.fu_berlin.inf.dpp.net.internal.XMPPChatTransmitter;
import de.fu_berlin.inf.dpp.net.internal.extensions.CancelInviteExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.ChecksumErrorExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.ChecksumExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.DataTransferExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.InviteExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.JoinExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.LeaveExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.PacketExtensionUtils;
import de.fu_berlin.inf.dpp.net.internal.extensions.RequestActivityExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.RequestForFileListExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.UserListExtension;
import de.fu_berlin.inf.dpp.observables.JingleFileTransferManagerObservable;
import de.fu_berlin.inf.dpp.observables.SessionIDObservable;
import de.fu_berlin.inf.dpp.observables.SharedProjectObservable;
import de.fu_berlin.inf.dpp.optional.cdt.CDTFacade;
import de.fu_berlin.inf.dpp.optional.jdt.JDTFacade;
import de.fu_berlin.inf.dpp.preferences.PreferenceManager;
import de.fu_berlin.inf.dpp.project.ConnectionSessionManager;
import de.fu_berlin.inf.dpp.project.SarosRosterListener;
import de.fu_berlin.inf.dpp.project.SessionManager;
import de.fu_berlin.inf.dpp.project.SharedResourcesManager;
import de.fu_berlin.inf.dpp.project.internal.RoleManager;
import de.fu_berlin.inf.dpp.ui.SarosUI;
import de.fu_berlin.inf.dpp.util.Util;
import de.fu_berlin.inf.dpp.util.pico.DotGraphMonitor;

/**
 * The main plug-in of Saros.
 * 
 * @author rdjemili
 * @author coezbek
 * 
 */
public class Saros extends AbstractUIPlugin {

    public static enum ConnectionState {
        NOT_CONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR
    }

    // The shared instance.
    protected static Saros plugin;

    public static final String SAROS = "de.fu_berlin.inf.dpp"; //$NON-NLS-1$

    public String sarosFeatureID;

    protected SessionManager sessionManager;

    protected MutablePicoContainer container;

    /**
     * To print an architecture diagram at the end of the plug-in life-cycle
     * initialize the dotMonitor with a new instance:
     * 
     * <code>dotMonitor= new DotGraphMonitor();</code>
     */
    protected DotGraphMonitor dotMonitor = null;

    /**
     * The reinjector used to inject dependencies for those objects that are
     * created by Eclipse and not by our PicoContainer.
     */
    protected Reinjector reinjector;

    protected XMPPConnection connection;

    protected JID myjid;

    protected ConnectionState connectionState = ConnectionState.NOT_CONNECTED;

    protected String connectionError;

    protected final List<IConnectionListener> listeners = new CopyOnWriteArrayList<IConnectionListener>();

    // Smack (XMPP) connection listener
    protected ConnectionListener smackConnectionListener;

    private Logger logger;

    static {
        PacketExtensionUtils.hookExtensionProviders();
        Roster.setDefaultSubscriptionMode(SubscriptionMode.accept_all);
    }

    /**
     * Create the shared instance.
     */
    public Saros() {
        setDefault(this);

        PicoBuilder picoBuilder = new PicoBuilder(new CompositeInjection(
            new ConstructorInjection(), new AnnotatedFieldInjection()))
            .withCaching();

        /*
         * If given, the dotMonitor is used to capture an architecture diagram
         * of the application
         */
        if (dotMonitor != null) {
            picoBuilder = picoBuilder.withMonitor(dotMonitor);
        }

        // Initialize our dependency injection container
        this.container = picoBuilder.build();

        /*
         * All singletons which exist for the whole plug-in life-cycle are
         * managed by PicoContainer for us.
         * 
         * The addComponent() calls are sorted alphabetically according to the
         * first argument. This makes it easier to search for a class without
         * tool support.
         */
        this.container.addComponent(CDTFacade.class);
        this.container.addComponent(ConnectionSessionManager.class);
        this.container.addComponent(ConsistencyWatchdogClient.class);
        this.container.addComponent(ConsistencyWatchdogServer.class);
        this.container.addComponent(SharedProjectObservable.class);
        this.container.addComponent(DataTransferManager.class);
        this.container.addComponent(EditorManager.class);
        this.container.addComponent(IsInconsistentObservable.class);
        this.container.addComponent(JDTFacade.class);
        this.container.addComponent(JingleFileTransferManagerObservable.class);
        this.container.addComponent(MultiUserChatManager.class);
        this.container.addComponent(MessagingManager.class);
        this.container.addComponent(PreferenceManager.class);
        this.container.addComponent(PreferenceUtils.class);
        this.container.addComponent(RoleManager.class);
        this.container.addComponent(Saros.class, this);
        this.container.addComponent(SarosRosterListener.class);
        this.container.addComponent(SarosUI.class);
        this.container.addComponent(SessionIDObservable.class);
        this.container.addComponent(SessionManager.class);
        this.container.addComponent(SessionViewOpener.class);
        this.container.addComponent(SharedResourcesManager.class);
        this.container.addComponent(SkypeManager.class);
        this.container.addComponent(SubscriptionListener.class);
        this.container.addComponent(XMPPChatReceiver.class);
        this.container.addComponent(XMPPChatTransmitter.class);

        // Handlers
        this.container.addComponent(CancelInviteHandler.class);
        this.container.addComponent(UserListHandler.class);
        this.container.addComponent(InvitationHandler.class);
        this.container.addComponent(LeaveHandler.class);
        this.container.addComponent(RequestForActivityHandler.class);
        this.container.addComponent(ConsistencyWatchdogHandler.class);
        this.container.addComponent(ChecksumHandler.class);

        // Extensions
        this.container.addComponent(ChecksumErrorExtension.class);
        this.container.addComponent(ChecksumExtension.class);
        this.container.addComponent(CancelInviteExtension.class);
        this.container.addComponent(UserListExtension.class);
        this.container.addComponent(RequestActivityExtension.class);
        this.container.addComponent(DataTransferExtension.class);
        this.container.addComponent(InviteExtension.class);
        this.container.addComponent(JoinExtension.class);
        this.container.addComponent(LeaveExtension.class);
        this.container.addComponent(RequestForFileListExtension.class);

        /*
         * The following classes are initialized by the re-injector because they
         * are created by Eclipse:
         * 
         * All User interface classes like all Views, but also
         * SharedDocumentProvider.
         * 
         * CAUTION: Classes from which duplicates can exists, should not be
         * managed by PicoContainer.
         */
        reinjector = new Reinjector(this.container);
    }

    /**
     * Injects dependencies into the annotated fields of the given object.
     */
    public static synchronized void reinject(Object toInjectInto) {

        if (plugin == null) {
            throw new IllegalStateException();
        }

        try {
            // Remove the component if an instance of it was already registered
            plugin.container.removeComponent(toInjectInto.getClass());

            // Add the given instance to the container
            plugin.container
                .addComponent(toInjectInto.getClass(), toInjectInto);

            /*
             * Ask PicoContainer to inject into the component via fields
             * annotated with @Inject
             */
            plugin.reinjector.reinject(toInjectInto.getClass(),
                new AnnotatedFieldInjection());
        } catch (PicoCompositionException e) {
            plugin.logger.error("Internal error in reinjection:", e);
        }
    }

    /**
     * This method is called upon plug-in activation
     */
    @Override
    public void start(BundleContext context) throws Exception {

        super.start(context);

        String version = Util.getBundleVersion(getBundle(), "Unknown Version");

        sarosFeatureID = plugin.toString() + "_" + version;

        XMPPConnection.DEBUG_ENABLED = getPreferenceStore().getBoolean(
            PreferenceConstants.DEBUG);

        setupLoggers();
        logger.info("Starting Saros " + version + " running:\n"
            + Util.getPlatformInfo());

        // Make sure that all components in the container are
        // instantiated
        container.getComponents(Object.class);

        this.sessionManager = container.getComponent(SessionManager.class);

        boolean hasUserName = getPreferenceStore().getString(
            PreferenceConstants.USERNAME).length() > 0;

        if (getPreferenceStore().getBoolean(PreferenceConstants.AUTO_CONNECT)
            && hasUserName) {
            asyncConnect();
        }
    }

    /**
     * This method is called when the plug-in is stopped
     */
    @Override
    public void stop(BundleContext context) throws Exception {

        if (dotMonitor != null) {
            File f = new File("Saros-" + sarosFeatureID + ".dot");
            logger.info("Saving Saros architecture diagram dot file: "
                + f.getAbsolutePath());
            dotMonitor.save(f);
        }

        try {
            disconnect();
        } finally {
            super.stop(context);
        }
        setDefault(null);
    }

    public static void setDefault(Saros newPlugin) {
        Saros.plugin = newPlugin;
    }

    public JID getMyJID() {
        return this.myjid;
    }

    public Roster getRoster() {
        if (!isConnected()) {
            return null;
        }

        return this.connection.getRoster();
    }

    /**
     * @nonBlocking
     */
    public void asyncConnect() {
        Util.runSafeAsync("Saros-AsyncConnect-", logger, new Runnable() {
            public void run() {
                connect(false);
            }
        });
    }

    /**
     * Connects using the credentials from the preferences.
     * 
     * If there is already a established connection when calling this method, it
     * disconnects before connecting (including state transitions!).
     * 
     * @blocking
     */
    public void connect(boolean failSilently) {

        IPreferenceStore prefStore = getPreferenceStore();
        final String server = prefStore.getString(PreferenceConstants.SERVER);
        final String username = prefStore
            .getString(PreferenceConstants.USERNAME);
        String password = prefStore.getString(PreferenceConstants.PASSWORD);

        try {
            if (isConnected()) {
                disconnect();
            }

            ConnectionConfiguration conConfig = new ConnectionConfiguration(
                server);
            conConfig.setReconnectionAllowed(false);
            this.connection = new XMPPConnection(conConfig);
            setConnectionState(ConnectionState.CONNECTING, null);

            this.connection.connect();

            ServiceDiscoveryManager sdm = ServiceDiscoveryManager
                .getInstanceFor(connection);

            sdm.addFeature(sarosFeatureID);

            // add Jingle feature to the supported extensions
            if (!prefStore
                .getBoolean(PreferenceConstants.FORCE_FILETRANSFER_BY_CHAT)) {

                // add Jingle Support for the current connection
                sdm.addFeature(Jingle.NAMESPACE);
            }

            // have to put this line to use new smack 3.1
            // without this line a NullPointerException happens but after a
            // longer time it connects anyway, with this line it connects fast
            // TODO security issue?
            SASLAuthentication.supportSASLMechanism("PLAIN", 0);

            this.connection.login(username, password);

            /*
             * TODO SS Possible race condition, as our packet listeners are
             * registered only after the login, so we might for instance receive
             * subscription requests even though we do not have a packet
             * listener running yet!
             */
            this.connection.getRoster().setSubscriptionMode(
                SubscriptionMode.manual);

            if (this.smackConnectionListener == null) {
                this.smackConnectionListener = new SafeConnectionListener(
                    logger, new XMPPConnectionListener());
            }

            this.connection.addConnectionListener(this.smackConnectionListener);
            setConnectionState(ConnectionState.CONNECTED, null);

            this.myjid = new JID(this.connection.getUser());

        } catch (final Exception e) {

            setConnectionState(ConnectionState.ERROR, e.getMessage());

            if (!failSilently) {
                Util.runSafeSWTSync(logger, new Runnable() {
                    public void run() {
                        MessageDialog.openError(EditorAPI.getShell(),
                            "Error Connecting", "Could not connect to server '"
                                + server + "' as user '" + username
                                + "'.\nErrorMessage was:\n" + e.getMessage());
                    }
                });
            }
        }
    }

    /**
     * Disconnects.
     * 
     * @blocking
     */
    public void disconnect() {
        setConnectionState(ConnectionState.DISCONNECTING, null);

        disconnectInternal();

        setConnectionState(ConnectionState.NOT_CONNECTED, null);

        this.myjid = null;
    }

    protected void disconnectInternal() {
        if (connection != null) {
            try {
                connection.removeConnectionListener(smackConnectionListener);
                connection.disconnect();
            } catch (RuntimeException e) {
                logger.warn("Could not disconnect old XMPPConnection: ", e);
            } finally {
                connection = null;
            }
        }
    }

    /**
     * Creates the given account on the given Jabber server.
     * 
     * @blocking
     * 
     * @param server
     *            the server on which to create the account.
     * @param username
     *            the username for the new account.
     * @param password
     *            the password for the new account.
     * @param monitor
     *            the progressmonitor for the operation.
     * @throws XMPPException
     *             exception that occcurs while registering.
     */
    public void createAccount(String server, String username, String password,
        IProgressMonitor monitor) throws XMPPException {

        monitor.beginTask("Registering account", 3);

        XMPPConnection connection = new XMPPConnection(server);
        monitor.worked(1);

        connection.connect();
        monitor.worked(1);

        connection.getAccountManager().createAccount(username, password);
        monitor.worked(1);

        connection.disconnect();
        monitor.done();
    }

    /**
     * Adds given contact to the roster.
     * 
     * @blocking
     * 
     * @param jid
     *            the Jabber ID of the contact.
     * @param nickname
     *            the nickname under which the new contact should appear in the
     *            roster.
     * @param groups
     *            the groups to which the new contact should belong to. This
     *            information will be saved on the server.
     * @throws XMPPException
     *             is thrown if no connection is established or the contact
     *             doesn't exist
     */
    public void addContact(JID jid, String nickname, String[] groups)
        throws XMPPException {
        assertConnection();

        // if roster already contains user with this jid do nothing
        if (connection.getRoster().contains(jid.toString())) {
            return;
        }

        // if discovering user information is successful add contact to
        // roster
        ServiceDiscoveryManager sdm = new ServiceDiscoveryManager(connection);
        try {
            if (sdm.discoverInfo(jid.toString()).getIdentities().hasNext()) {
                connection.getRoster().createEntry(jid.toString(), nickname,
                    groups);
            }
        } catch (XMPPException e) {
            // if server doesn't support to get information add contact
            // anyway (if entry would't exist it should be an error 404)
            if (e.getMessage().contains("501"))/* feature-not-implemented */{
                connection.getRoster().createEntry(jid.toString(), nickname,
                    groups);
            } else
                throw e;
        }
    }

    /**
     * Removes given contact from the roster.
     * 
     * @blocking
     * 
     * @param rosterEntry
     *            the contact that is to be removed
     * @throws XMPPException
     *             is thrown if no connection is establised.
     */
    public void removeContact(RosterEntry rosterEntry) throws XMPPException {
        assertConnection();
        this.connection.getRoster().removeEntry(rosterEntry);
    }

    public boolean isConnected() {
        return this.connectionState == ConnectionState.CONNECTED;
    }

    /**
     * @return the current state of the connection.
     */
    public ConnectionState getConnectionState() {
        return this.connectionState;
    }

    /**
     * @return an error string that contains the error message for the current
     *         connection error if the state is {@link ConnectionState#ERROR} or
     *         <code>null</code> if there is another state set.
     */
    public String getConnectionError() {
        return this.connectionError;
    }

    /**
     * @return the currently established connection or <code>null</code> if
     *         there is none.
     */
    public XMPPConnection getConnection() {
        return this.connection;
    }

    public void addListener(IConnectionListener listener) {
        if (!this.listeners.contains(listener)) {
            this.listeners.add(listener);
        }
    }

    public void removeListener(IConnectionListener listener) {
        this.listeners.remove(listener);
    }

    protected void assertConnection() throws XMPPException {
        if (!isConnected()) {
            throw new XMPPException("No connection");
        }
    }

    /**
     * Sets a new connection state and notifies all connection listeners.
     */
    protected void setConnectionState(ConnectionState state, String error) {
        this.connectionState = state;
        this.connectionError = error;

        for (IConnectionListener listener : this.listeners) {
            try {
                listener.connectionStateChanged(this.connection, state);
            } catch (RuntimeException e) {
                logger.error("Internal error in setConnectionState:", e);
            }
        }
    }

    protected void setupLoggers() {
        try {
            PropertyConfigurator.configureAndWatch("log4j.properties",
                60 * 1000);
            logger = Logger.getLogger("de.fu_berlin.inf.dpp");

        } catch (SecurityException e) {
            System.err.println("Could not start logging:");
            e.printStackTrace();
        }
    }

    /**
     * Log a message to the Eclipse ErrorLog. This method should be used to log
     * all errors that occur in the plugin that cannot be corrected by the user
     * and seem to be errors within the plug-in or the used libraries.
     * 
     * @param message
     *            A meaningful description of during which operation the error
     *            occurred
     * @param e
     *            The exception associated with the error (may be null)
     */
    public static void log(String message, Exception e) {
        plugin.getLog().log(
            new Status(IStatus.ERROR, Saros.SAROS, IStatus.ERROR, message, e));
    }

    protected class XMPPConnectionListener implements ConnectionListener {

        public void connectionClosed() {
            // self inflicted, controlled disconnect
            setConnectionState(ConnectionState.NOT_CONNECTED, null);
        }

        public void connectionClosedOnError(Exception e) {

            Toolkit.getDefaultToolkit().beep();
            logger.error("XMPP Connection Error: " + e.toString(), e);

            if (e.toString().equals("stream:error (conflict)")) {

                disconnect();

                Util.runSafeSWTSync(logger, new Runnable() {
                    public void run() {
                        MessageDialog
                            .openError(
                                EditorAPI.getShell(),
                                "Connection error",
                                "There is a conflict with the jabber connection."
                                    + "The reason for this is mostly that another saros "
                                    + "instance have connected with the same login.");
                    }
                });
                return;
            }
            setConnectionState(ConnectionState.ERROR, null);

            disconnectInternal();

            Util.runSafeAsync(logger, new Runnable() {
                public void run() {

                    Map<JID, Integer> expectedSequenceNumbers = Collections
                        .emptyMap();
                    if (sessionManager.getSharedProject() != null) {
                        expectedSequenceNumbers = sessionManager
                            .getSharedProject().getSequencer()
                            .getExpectedSequenceNumbers();
                    }

                    while (!isConnected()) {

                        logger.info("Reconnecting...");

                        connect(true);

                        if (!isConnected()) {
                            try {
                                Thread.sleep(5000);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    }

                    sessionManager.onReconnect(expectedSequenceNumbers);
                    setConnectionState(ConnectionState.CONNECTED, null);
                    logger.debug("XMPP reconnected");
                }
            });
        }

        public void reconnectingIn(int seconds) {
            // TODO maybe using Smack reconnect is better
            assert false : "Reconnecting is disabled";
            // logger.debug("saros reconnecting");
            // setConnectionState(ConnectionState.CONNECTING, null);
        }

        public void reconnectionFailed(Exception e) {
            // TODO maybe using Smack reconnect is better
            assert false : "Reconnecting is disabled";
            // logger.debug("saros reconnection failed");
            // setConnectionState(ConnectionState.ERROR, e.getMessage());
        }

        public void reconnectionSuccessful() {
            // TODO maybe using Smack reconnect is better
            assert false : "Reconnecting is disabled";
            // logger.debug("saros reconnection successful");
            // setConnectionState(ConnectionState.CONNECTED, null);
        }
    }

    public static boolean getFileTransferModeViaChat() {
        return plugin.getPreferenceStore().getBoolean(
            PreferenceConstants.FORCE_FILETRANSFER_BY_CHAT);
    }

}
