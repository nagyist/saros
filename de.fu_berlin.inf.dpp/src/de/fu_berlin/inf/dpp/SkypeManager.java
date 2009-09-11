package de.fu_berlin.inf.dpp;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.jivesoftware.smack.PacketCollector;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.Roster;
import org.jivesoftware.smack.RosterEntry;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.filter.PacketIDFilter;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;

import de.fu_berlin.inf.dpp.annotations.Component;
import de.fu_berlin.inf.dpp.net.ConnectionState;
import de.fu_berlin.inf.dpp.net.IConnectionListener;
import de.fu_berlin.inf.dpp.net.JID;
import de.fu_berlin.inf.dpp.net.internal.XStreamExtensionProvider;
import de.fu_berlin.inf.dpp.net.internal.XStreamExtensionProvider.XStreamIQPacket;
import de.fu_berlin.inf.dpp.preferences.PreferenceConstants;
import de.fu_berlin.inf.dpp.util.Util;

/**
 * A manager class that allows to discover if a given XMPP entity supports Skype
 * and that allows to initiate Skype VOIP sessions with that entity.
 * 
 * TODO CO: Verify that IQ Packets are the best way of doing this. It seems kind
 * of hackisch. Could we also use ServiceDiscovery?
 * 
 * @author rdjemili
 * @author oezbek
 * 
 */
@Component(module = "net")
public class SkypeManager implements IConnectionListener {

    protected XStreamExtensionProvider<String> skypeProvider = new XStreamExtensionProvider<String>(
        "skypeInfo");

    protected final Map<JID, String> skypeNames = new HashMap<JID, String>();

    protected Saros saros;

    public SkypeManager(Saros saros) {
        this.saros = saros;
        saros.addListener(this);

        /**
         * Register for our preference store, so we can be notified if the Skype
         * Username changes.
         */
        IPreferenceStore prefs = saros.getPreferenceStore();
        prefs.addPropertyChangeListener(new IPropertyChangeListener() {

            public void propertyChange(PropertyChangeEvent event) {
                if (event.getProperty().equals(
                    PreferenceConstants.SKYPE_USERNAME)) {
                    publishSkypeIQ(event.getNewValue().toString());
                }
            }
        });

    }

    /**
     * Returns the Skype-URL for given roster entry. This method will query all
     * presences associated with the given roster entry and return the first
     * valid Skype url (if any).
     * 
     * The order in which the presences are queried is undefined. If you need
     * more control use getSkypeURL(JID).
     * 
     * @return the skype url for given roster entry or <code>null</code> if
     *         roster entry has no skype name.
     * 
     *         This method will return previously cached results.
     * 
     * @blocking This method is potentially long-running
     */
    public String getSkypeURL(RosterEntry rosterEntry) {

        XMPPConnection connection = saros.getConnection();
        if (connection == null)
            return null;

        Roster roster = connection.getRoster();
        if (roster == null)
            return null;

        for (Presence presence : Util.asIterable(roster
            .getPresences(rosterEntry.getUser()))) {
            if (presence.isAvailable()) {
                JID jid = new JID(presence.getFrom());

                String result = getSkypeURL(jid);
                if (result != null)
                    return result;
            }
        }

        return null;
    }

    /**
     * Returns the Skype-URL for user identified by the given RQ-JID.
     * 
     * @return the skype url for given JID or <code>null</code> if the user has
     *         no skype name set for this client.
     * 
     *         This method will return previously cached results.
     * 
     * @blocking This method is potentially long-running
     */
    public String getSkypeURL(JID rqJID) {

        XMPPConnection connection = saros.getConnection();
        if (connection == null)
            return null;

        String skypeName;

        if (this.skypeNames.containsKey(rqJID)) {
            skypeName = this.skypeNames.get(rqJID);
        } else {
            skypeName = requestSkypeName(connection, rqJID);
            if (skypeName != null) {
                // Only cache if we found something
                this.skypeNames.put(rqJID, skypeName);
            }
        }

        if (skypeName == null)
            return null;

        return "skype:" + skypeName;
    }

    /**
     * Send the given Skype user name to all our contacts that are currently
     * available.
     * 
     * TODO SS only send to those, that we know use Saros.
     */
    public void publishSkypeIQ(String newSkypeName) {
        XMPPConnection connection = saros.getConnection();
        if (connection == null)
            return;

        Roster roster = connection.getRoster();
        if (roster == null)
            return;

        for (RosterEntry rosterEntry : roster.getEntries()) {
            for (Presence presence : Util.asIterable(roster
                .getPresences(rosterEntry.getUser()))) {
                if (presence.isAvailable()) {
                    IQ result = skypeProvider.createIQ(newSkypeName);
                    result.setType(IQ.Type.SET);
                    result.setTo(presence.getFrom());
                    connection.sendPacket(result);
                }
            }
        }
    }

    /**
     * Register a new PacketListener for intercepting SkypeIQ packets.
     */
    public void connectionStateChanged(final XMPPConnection connection,
        ConnectionState newState) {

        if (newState == ConnectionState.CONNECTED) {
            connection.addPacketListener(new PacketListener() {

                public void processPacket(Packet packet) {

                    @SuppressWarnings("unchecked")
                    XStreamIQPacket<String> iq = (XStreamIQPacket<String>) packet;

                    if (iq.getType() == IQ.Type.GET) {
                        IQ reply = skypeProvider.createIQ(getLocalSkypeName());
                        reply.setType(IQ.Type.RESULT);
                        reply.setPacketID(iq.getPacketID());
                        reply.setTo(iq.getFrom());

                        connection.sendPacket(reply);
                    }
                    if (iq.getType() == IQ.Type.SET) {
                        String skypeName = iq.getPayload();
                        if (skypeName != null && skypeName.length() > 0) {
                            skypeNames.put(new JID(iq.getFrom()), skypeName);
                        } else {
                            skypeNames.remove(new JID(iq.getFrom()));
                        }
                    }
                }
            }, skypeProvider.getIQFilter());
        } else {
            // Otherwise clear our cache
            skypeNames.clear();
        }
    }

    /**
     * @return the local Skype name or <code>null</code> if none is set.
     */
    protected String getLocalSkypeName() {
        IPreferenceStore prefs = saros.getPreferenceStore();
        return prefs.getString(PreferenceConstants.SKYPE_USERNAME);
    }

    /**
     * Requests the Skype user name of given user. This method blocks up to 5
     * seconds to receive the value.
     * 
     * @param rqJID
     *            the rqJID of the user for which the Skype name is requested.
     * @return the Skype user name of given user or <code>null</code> if the
     *         user doesn't respond in time (5s) or has no Skype name.
     */
    protected String requestSkypeName(XMPPConnection connection, JID rqJID) {

        if ((connection == null) || !connection.isConnected()) {
            return null;
        }

        // Request the time from a remote user.
        IQ request = skypeProvider.createIQ(null);

        request.setType(IQ.Type.GET);
        request.setTo(rqJID.toString());

        // Create a packet collector to listen for a response.
        PacketCollector collector = connection
            .createPacketCollector(new PacketIDFilter(request.getPacketID()));

        try {
            connection.sendPacket(request);

            // Wait up to 5 seconds for a result.
            String skypeName = skypeProvider.getPayload(collector
                .nextResult(5000));

            if (skypeName == null || skypeName.trim().length() == 0)
                return null;
            else
                return skypeName.trim();
        } finally {
            collector.cancel();
        }
    }
}
