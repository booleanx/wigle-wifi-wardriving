package net.wigle.wigleandroid.listener;

import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.LocationManager.NETWORK_PROVIDER;
import net.wigle.wigleandroid.ListActivity;
import net.wigle.wigleandroid.R;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.GpsStatus.Listener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.widget.Toast;

public class GPSListener implements Listener, LocationListener {
  private static final long GPS_TIMEOUT = 15000L;
  private static final long NET_LOC_TIMEOUT = 60000L;
  
  private ListActivity listActivity;
  private Location location;
  private Location networkLocation;
  private GpsStatus gpsStatus;
  // set these times to avoid NPE in locationOK() seen by <DooMMasteR>
  private Long lastLocationTime = 0L;
  private Long lastNetworkLocationTime = 0L;
  private Long satCountLowTime = 0L;
  private float previousSpeed = 0f;
  private LocationListener mapLocationListener;
  
  public GPSListener( ListActivity listActivity ) {
    this.listActivity = listActivity;
  }
  
  public void setMapListener( LocationListener mapLocationListener ) {
    this.mapLocationListener = mapLocationListener;
  }
  
  public void setListActivity( ListActivity listActivity ) {
    this.listActivity = listActivity;
  }
  
  @Override
  public void onGpsStatusChanged( final int event ) {
    if ( event == GpsStatus.GPS_EVENT_STOPPED ) {
      ListActivity.info("GPS STOPPED");    
      // this event lies, on one device it gets called when the
      // network provider is disabled :(  so we do nothing...
      // listActivity.setLocationUpdates();
    }
    // ListActivity.info("GPS event: " + event);
    updateLocationData( (Location) null );
  } 
  
  public void handleScanStop() {
    ListActivity.info("GPSListener: handleScanStop");
    gpsStatus = null;
    location = null;
  }
  
  @Override
  public void onLocationChanged( final Location newLocation ) {
    // ListActivity.info("GPS onLocationChanged: " + newLocation);
    updateLocationData( newLocation );
        
    if ( mapLocationListener != null ) {
      mapLocationListener.onLocationChanged( newLocation );
    }
  }
  
  @Override
  public void onProviderDisabled( final String provider ) {
    ListActivity.info("provider disabled: " + provider);
        
    if ( mapLocationListener != null ) {
      mapLocationListener.onProviderDisabled( provider );
    }
  }
  
  @Override
  public void onProviderEnabled( final String provider ) {
    ListActivity.info("provider enabled: " + provider);
        
    if ( mapLocationListener != null ) {
      mapLocationListener.onProviderEnabled( provider );
    }
  }
  
  @Override
  public void onStatusChanged( final String provider, final int status, final Bundle extras ) {
    ListActivity.info("provider status changed: " + provider + " status: " + status);
        
    if ( mapLocationListener != null ) {
      mapLocationListener.onStatusChanged( provider, status, extras );
    }
  }

  /** newLocation can be null */
  private void updateLocationData( final Location newLocation ) {
    final LocationManager locationManager = (LocationManager) listActivity.getSystemService(Context.LOCATION_SERVICE);
    // see if we have new data
    gpsStatus = locationManager.getGpsStatus( gpsStatus );
    final int satCount = getSatCount();
    
    boolean newOK = newLocation != null;
    final boolean locOK = locationOK( location, satCount );
    final long now = System.currentTimeMillis();
    
    if ( newOK ) {
      if ( NETWORK_PROVIDER.equals( newLocation.getProvider() ) ) {
        // save for later, in case we lose gps
        networkLocation = newLocation;
        lastNetworkLocationTime = now;
      }
      else {
        lastLocationTime = now;
        // make sure there's enough sats on this new gps location
        newOK = locationOK( newLocation, satCount );
      }
    }
    
    if ( listActivity.inEmulator() && newLocation != null ) {
      newOK = true; 
    }
    
    final boolean netLocOK = locationOK( networkLocation, satCount );
    
    boolean wasProviderChange = false;
    if ( ! locOK ) {
      if ( newOK ) {
        wasProviderChange = true;
        if ( location != null && ! location.getProvider().equals( newLocation.getProvider() ) ) {
          wasProviderChange = false;
        }
        
        location = newLocation;
      }
      else if ( netLocOK ) {
        location = networkLocation;
        wasProviderChange = true;
      }
      else if ( location != null ) {
        // transition to null
        ListActivity.info( "nulling location: " + location );
        location = null;
        wasProviderChange = true;
        // make sure we're registered for updates
        listActivity.setLocationUpdates();
      }
    }
    else if ( newOK && GPS_PROVIDER.equals( newLocation.getProvider() ) ) {
      if ( NETWORK_PROVIDER.equals( location.getProvider() ) ) {
        // this is an upgrade from network to gps
        wasProviderChange = true;
      }
      location = newLocation;
      if ( wasProviderChange ) {
        // save it in prefs
        saveLocation();
      }
    }
    else if ( newOK && NETWORK_PROVIDER.equals( newLocation.getProvider() ) ) {
      if ( NETWORK_PROVIDER.equals( location.getProvider() ) ) {
        // just a new network provided location over an old one
        location = newLocation;
      }
    }
    
    // for maps. so lame!
    ListActivity.lameStatic.location = location;
    boolean scanScheduled = false;
    if ( location != null ) {
       final float currentSpeed = location.getSpeed();
       if ( (previousSpeed == 0f && currentSpeed > 0f)
          || (previousSpeed < 5f && currentSpeed >= 5f)) {
        // moving faster now than before, schedule a scan because the timing config pry changed
        ListActivity.info("Going faster, scheduling scan");
        listActivity.scheduleScan();
        scanScheduled = true;
      }
      previousSpeed = currentSpeed;
    }
    else {
      previousSpeed = 0f;
    }
    
    // ListActivity.info("sat count: " + satCount);
    
    if ( wasProviderChange ) {
      ListActivity.info( "wasProviderChange: satCount: " + satCount 
        + " newOK: " + newOK + " locOK: " + locOK + " netLocOK: " + netLocOK
        + " wasProviderChange: " + wasProviderChange
        + (newOK ? " newProvider: " + newLocation.getProvider() : "")
        + (locOK ? " locProvider: " + location.getProvider() : "") 
        + " newLocation: " + newLocation );

      final String announce = location == null ? listActivity.getString(R.string.lost_location) 
          : listActivity.getString(R.string.have_location) + " \"" + location.getProvider() + "\"";
      Toast.makeText( listActivity, announce, Toast.LENGTH_SHORT ).show();
      final SharedPreferences prefs = listActivity.getSharedPreferences( ListActivity.SHARED_PREFS, 0 );
      final boolean speechGPS = prefs.getBoolean( ListActivity.PREF_SPEECH_GPS, true );
      if ( speechGPS ) {
        // no quotes or the voice pauses
        final String speakAnnounce = location == null ? "Lost Location" 
          : "Now have location from " + location.getProvider() + ".";
        listActivity.speak( speakAnnounce );
      }
      
      if ( ! scanScheduled ) {
        // get the ball rolling
        ListActivity.info("Location provider change, scheduling scan");
        listActivity.scheduleScan();
      }
    }
    
    // update the UI
    listActivity.setLocationUI();
  }
  
  public void checkLocationOK() {
    if ( ! locationOK( location, getSatCount() ) ) {
      // do a self-check
      updateLocationData(null);
    }
  }
  
  private boolean locationOK( final Location location, final int satCount ) {
    boolean retval = false;
    final long now = System.currentTimeMillis();
    
    if ( location == null ) {
      // bad!
    }
    else if ( GPS_PROVIDER.equals( location.getProvider() ) ) {
      if ( satCount > 0 && satCount < 3 ) {
        if ( satCountLowTime == null ) {
          satCountLowTime = now;
        }
      }
      else {
        // plenty of sats
        satCountLowTime = null;
      }
      boolean gpsLost = satCountLowTime != null && (now - satCountLowTime) > GPS_TIMEOUT;
      gpsLost |= now - lastLocationTime > GPS_TIMEOUT;
      gpsLost |= horribleGps(location);
      retval = ! gpsLost;
    }
    else if ( NETWORK_PROVIDER.equals( location.getProvider() ) ) {
      boolean gpsLost = now - lastNetworkLocationTime > NET_LOC_TIMEOUT;
      gpsLost |= horribleGps(location);      
      retval = ! gpsLost;
    }
    
    return retval;
  }
  
  private boolean horribleGps(final Location location) {
    // try to protect against some horrible gps's out there
    boolean horrible = false;
    // check if accuracy is under 10 miles
    horrible |= location.hasAccuracy() && location.getAccuracy() > 16000;
    horrible |= location.getLatitude() < -90 || location.getLatitude() > 90;
    horrible |= location.getLongitude() < -180 || location.getLongitude() > 180;
    return horrible;
  }
  
  public int getSatCount() {
    int satCount = 0;
    if ( gpsStatus != null ) {
      for ( GpsSatellite sat : gpsStatus.getSatellites() ) {
        if ( sat.usedInFix() ) {
          satCount++;
        }
      }
    }
    return satCount;
  }
  
  public void saveLocation() {
    // save our location for use on later runs
    if ( this.location != null ) {
      final SharedPreferences prefs = listActivity.getSharedPreferences( ListActivity.SHARED_PREFS, 0 );
      final Editor edit = prefs.edit();
      // there is no putDouble
      edit.putFloat( ListActivity.PREF_PREV_LAT, (float) location.getLatitude() );
      edit.putFloat( ListActivity.PREF_PREV_LON, (float) location.getLongitude() );
      edit.commit();
    }
  }
  
  public Location getLocation() {
    return location;
  }
  
}
