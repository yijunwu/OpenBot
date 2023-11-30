package org.openbot.env;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class ConnectionSelector {
  private static final String TAG = "ConnectionManager";
  private static ConnectionSelector _connectionSelector;
  private static Context _context;
  private List<ILocalConnection> connections;

  private final ILocalConnection networkConnection = new NetworkServiceConnection();
  private final ILocalConnection nearbyConnection = new NearbyConnection();

  private ConnectionSelector() {
    if (_connectionSelector != null) {
      throw new RuntimeException(
          "Use getInstance() method to get the single instance of this class.");
    }
  }

  public static ConnectionSelector getInstance(Context context) {
    _context = context;
    if (_connectionSelector == null) {

      synchronized (ConnectionSelector.class) {
        if (_connectionSelector == null) _connectionSelector = new ConnectionSelector();
      }
    }

    return _connectionSelector;
  }

  @NotNull
  List<ILocalConnection> getConnections() {
    if (connections != null) {
      return connections;
    }

    connections = new ArrayList<>();
    if (isConnectedViaWifi()) {
      connections.add(networkConnection);
    } else {
      connections.add(nearbyConnection);
    }

    return connections;
  }

  private boolean isConnectedViaWifi() {
    ConnectivityManager connectivityManager =
        (ConnectivityManager) _context.getSystemService(Context.CONNECTIVITY_SERVICE);
    NetworkInfo mWifi = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
    return mWifi.isConnected();
  }
}
