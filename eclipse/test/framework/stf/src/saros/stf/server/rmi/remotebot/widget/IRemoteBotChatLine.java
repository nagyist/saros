package saros.stf.server.rmi.remotebot.widget;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface IRemoteBotChatLine extends Remote {

  public String getText() throws RemoteException;
}
