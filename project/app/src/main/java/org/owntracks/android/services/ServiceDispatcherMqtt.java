package org.owntracks.android.services;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistable;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.MqttPingSender;
import org.eclipse.paho.client.mqttv3.internal.ClientComms;
import org.greenrobot.eventbus.Subscribe;
import org.json.JSONException;
import org.json.JSONObject;
import org.owntracks.android.App;
import org.owntracks.android.messages.MessageBase;
import org.owntracks.android.messages.MessageCard;
import org.owntracks.android.messages.MessageClear;
import org.owntracks.android.services.ServiceMessage.EndpointState;
import org.owntracks.android.support.Events;
import org.owntracks.android.support.Parser;
import org.owntracks.android.support.Preferences;
import org.owntracks.android.support.SocketFactory;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.TimeUnit;

import timber.log.Timber;

public class ServiceDispatcherMqtt {
	private static final String TAG = "ServiceMessageMqtt";
	public static final String RECEIVER_ACTION_CLEAR_CONTACT_EXTRA_TOPIC = "RECEIVER_ACTION_CLEAR_CONTACT_EXTRA_TOPIC" ;
	public static final String RECEIVER_ACTION_CLEAR_CONTACT = "RECEIVER_ACTION_CLEAR_CONTACT";

	private MqttAsyncClient mqttClient;
	private Object error;
	private MqttConnectOptions connectOptions;
	private boolean cleanSession;
	private String lastConnectionId;
	private static EndpointState state;

	public static void sendMessage(Bundle b) {
		getInstance().handle(b);
	}

	private void handle(Bundle b) {

	}

	private static ServiceDispatcherMqtt instance;
	public static ServiceDispatcherMqtt getInstance() {
		if(instance == null)
			instance = new ServiceDispatcherMqtt();
		return instance;
	}

	private MqttCallbackExtended iCallbackClient = new MqttCallbackExtended() {
		@Override
		public void connectComplete(boolean reconnect, String serverURI) {
			Timber.v("%s, serverUri:%s", reconnect, serverURI);
		}

		@Override
		public void deliveryComplete(IMqttDeliveryToken token) {

		}

		@Override
		public void connectionLost(Throwable cause) {
			Timber.e(cause, "connectionLost error");
			changeState(EndpointState.DISCONNECTED, new Exception(cause));
			// TODO: schedule reconnect through dispatcher
		}

		@Override
		public void messageArrived(String topic, MqttMessage message) throws Exception {
			if(message.getPayload().length > 0) {
				try {
					MessageBase m = Parser.fromJson(message.getPayload());
					if (!m.isValidMessage()) {
						Timber.e("message failed validation");
						return;
					}

					m.setTopic(topic);
					m.setRetained(message.isRetained());
					m.setQos(message.getQos());
					//TODO: send to repo
					//service.onMessageReceived(m);
				} catch (Exception e) {
					Timber.e(e, "payload:%s ", new String(message.getPayload()));

				}
			} else {
				MessageClear m = new MessageClear();
				m.setTopic(topic.replace(MessageCard.BASETOPIC_SUFFIX, ""));
				Timber.v("clear message received: %s", m.getTopic());
				//TODO: send to repo
				//service.onMessageReceived(m);
			}
		}

	};


	private void handleStart() {
		Log.v(TAG, "handleStart");
        if(!Preferences.canConnect()) {
			changeState(EndpointState.ERROR_CONFIGURATION);
			return;
        }

		if (isConnecting()) {
			Log.d(TAG, "handleStart: isConnecting:true");
			return;
		}

		// Check if there is a data connection. If not, try again in some time.
		if (!isOnline()) {
			Log.e(TAG, "handleStart: isOnline:false");
			changeState(EndpointState.ERROR_DATADISABLED);
			return;
		}

		if (isDisconnected()) {
				Log.v(TAG, "handleStart: isDisconnected:true");
				changeState(EndpointState.DISCONNECTED);

				if (connect())
					onConnect();
		} else {
			Log.d(TAG, "handleStart: isDisconnected() == false");
		}
	}

	private boolean isDisconnected() {
		return this.mqttClient == null || !this.mqttClient.isConnected();
	}

	private boolean init() {
		if (this.mqttClient != null) {
			return true;
		}

		try {
			String prefix = "tcp";
			if (Preferences.getTls()) {
				if (Preferences.getWs()) {
					prefix = "wss";
				} else
					prefix = "ssl";
			} else {
				if (Preferences.getWs())
					prefix = "ws";
			}

			String cid = Preferences.getClientId(true);
            String connectString = prefix + "://" + Preferences.getHost() + ":" + Preferences.getPort();
			Log.v(TAG, "init() mode: " + Preferences.getModeId());
			Log.v(TAG, "init() client id: " + cid);
			Log.v(TAG, "init() connect string: " + connectString);

			this.mqttClient = new MqttAsyncClient(connectString, cid);
			this.mqttClient.setCallback(iCallbackClient);
			Timber.v("clientInstance:%s", this.mqttClient);
		} catch (Exception e) {
			// something went wrong!
			this.mqttClient = null;
			changeState(e);
            return false;
		}
        return true;
	}

	private boolean connect() {
        changeState(EndpointState.CONNECTING);

		error = null; // clear previous error on connect
		if(!init()) {
            return false;
        }

		try {
			 connectOptions = new MqttConnectOptions();
			if (Preferences.getAuth()) {
				connectOptions.setPassword(Preferences.getPassword().toCharArray());
				connectOptions.setUserName(Preferences.getUsername());
			}

			connectOptions.setMqttVersion(Preferences.getMqttProtocolLevel());

			if (Preferences.getTls()) {
				String tlsCaCrt = Preferences.getTlsCaCrtName();
				String tlsClientCrt = Preferences.getTlsClientCrtName();

				SocketFactory.SocketFactoryOptions socketFactoryOptions = new SocketFactory.SocketFactoryOptions();

				if (tlsCaCrt.length() > 0) {
					try {
						socketFactoryOptions.withCaInputStream(App.getContext().openFileInput(tlsCaCrt));
					} catch (FileNotFoundException e) {
						e.printStackTrace();
					}
				}

				if (tlsClientCrt.length() > 0) {
					try {
						socketFactoryOptions.withClientP12InputStream(App.getContext().openFileInput(tlsClientCrt)).withClientP12Password(Preferences.getTlsClientCrtPassword());
					} catch (FileNotFoundException e1) {
						e1.printStackTrace();
					}
				}



				connectOptions.setSocketFactory(new SocketFactory(socketFactoryOptions));
			}


            setWill(connectOptions);
			connectOptions.setKeepAliveInterval(Preferences.getKeepalive());
			connectOptions.setConnectionTimeout(30);
			cleanSession = Preferences.getCleanSession();
			connectOptions.setCleanSession(cleanSession);

			this.mqttClient.connect(connectOptions).waitForCompletion();
			changeState(EndpointState.CONNECTED);

			return true;

		} catch (Exception e) { // Catch paho and socket factory exceptions
			Log.e(TAG, e.toString());
            e.printStackTrace();
			changeState(e);
			return false;
		}
	}

	private void setWill(MqttConnectOptions m) {
        try {
            JSONObject lwt = new JSONObject();
            lwt.put("_type", "lwt");
            lwt.put("tst", (int) TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()));

            m.setWill(Preferences.getPubTopicBase(), lwt.toString().getBytes(), 0, false);
        } catch(JSONException ignored) {}

	}

	private String getConnectionId() {
		return mqttClient.getCurrentServerURI()+"/"+ connectOptions.getUserName();
	}

	private void onConnect() {

		// Check if we're connecting to the same broker that we were already connected to
		String connectionId = getConnectionId();
		if(lastConnectionId != null && !connectionId.equals(lastConnectionId)) {
			App.getEventBus().post(new Events.BrokerChanged());
			lastConnectionId = connectionId;
			Log.v(TAG, "lastConnectionId changed to: " + lastConnectionId);
		}

		// Establish observer to monitor wifi and radio connectivity
		if (cleanSession)
			onCleanSessionConnect();
		else
			onUncleanSessionConnect();

		onSessionConnect();
	}


	private void onCleanSessionConnect() {
	}

	private void onUncleanSessionConnect() {
	}

	private void onSessionConnect() {
		subscribToInitialTopics();
	}

	private void subscribToInitialTopics() {
		List<String> topics = new ArrayList<>();
		String subTopicBase = Preferences.getSubTopic();

		if(!Preferences.getSub()) // Don't subscribe if base topic is invalid
			return;
		else if(subTopicBase.endsWith("#")) { // wildcard sub will match everything anyway
			topics.add(subTopicBase);
		} else {

			topics.add(subTopicBase);
			if(Preferences.getInfo())
				topics.add(subTopicBase + Preferences.getPubTopicInfoPart());

			if (!Preferences.isModeMqttPublic())
				topics.add(Preferences.getPubTopicBase() + Preferences.getPubTopicCommandsPart());

			if (!Preferences.isModeMqttPublic()) {
				topics.add(subTopicBase + Preferences.getPubTopicEventsPart());
				topics.add(subTopicBase + Preferences.getPubTopicWaypointsPart());
			}


		}

		subscribe(topics.toArray(new String[topics.size()]));

	}


    private void subscribe(String[] topics) {
		if(!isConnected()) {
            Log.e(TAG, "subscribe when not connected");
            return;
        }
        for(String s : topics) {
            Log.v(TAG, "subscribe() - Will subscribe to: " + s);
        }
		try {
			int qos[] = getSubTopicsQos(topics);

			this.mqttClient.subscribe(topics, qos);

		} catch (Exception e) {
			e.printStackTrace();
		}
    }


	private int[] getSubTopicsQos(String[] topics) {
		int[] qos = new int[topics.length];
		Arrays.fill(qos, Preferences.getSubQos());
		return qos;
	}

	@SuppressWarnings("unused")
	private void unsubscribe(String[] topics) {
		if(!isConnected()) {
			Log.e(TAG, "subscribe when not connected");
			return;
		}

		for(String s : topics) {
			Log.v(TAG, "unsubscribe() - Will unsubscribe from: " + s);
		}

		try {
			mqttClient.unsubscribe(topics);
		} catch (Exception e) {
			e.printStackTrace();
		}
    }


	private void disconnect(boolean fromUser) {

		Timber.v("disconnect. user:%s", fromUser);
		if (isConnecting()) {
            return;
        }

		try {
			if (isConnected()) {
				Log.v(TAG, "Disconnecting");
				this.mqttClient.disconnect(0);
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			this.mqttClient = null;

			if (fromUser)
				changeState(EndpointState.DISCONNECTED_USERDISCONNECT);
			else
				changeState(EndpointState.DISCONNECTED);
		}
	}



	public void reconnect() {
		disconnect(false);
		handleStart();
	}

	private void changeState(Exception e) {
		error = e;
		changeState(EndpointState.ERROR, e);
	}

	private void changeState(EndpointState newState) {
		changeState(newState, null);
	}

	private void changeState(EndpointState newState, Exception e) {
		state = newState;
		//if(service != null)
		//	service.onEndpointStateChanged(newState, e);
	}

	private boolean isOnline() {
		ConnectivityManager cm = (ConnectivityManager) App.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo netInfo = cm.getActiveNetworkInfo();

        if(netInfo != null && netInfo.isAvailable() && netInfo.isConnected()) {
            return true;
        } else {
            Log.e(TAG, "isOnline == false. activeNetworkInfo: "+ (netInfo != null) +", available:" + (netInfo != null && netInfo.isAvailable()) + ", connected:" + (netInfo != null && netInfo.isConnected()));
            return false;
        }
	}

	private boolean isConnected() {
		return this.mqttClient != null && this.mqttClient.isConnected(  );
	}

	private boolean isConnecting() {
		return (this.mqttClient != null) && (state == EndpointState.CONNECTING);
	}

	public static EndpointState getState() {
		return state;
	}

	@Override
	public boolean isReady() {
		return this.service != null && this.mqttClient != null;
	}


	public Exception getError() {
        return error;
    }




	@Subscribe
	public void onEvent(Events.Dummy e) {
	}


	private void clearQueues() {
		initPausedPubPool();
    }


	@Subscribe
	public void onEvent(Events.BrokerChanged e) {
        clearQueues();
    }



    // Custom blocking MqttClient that allows to specify a MqttPingSender
    private static final class CustomMqttClient extends MqttAsyncClient {
        CustomMqttClient(String serverURI, String clientId, MqttClientPersistence persistence, MqttPingSender pingSender) throws MqttException {
            super(serverURI, clientId, persistence, pingSender);// Have to call do the AsyncClient init twice as there is no other way to setup a client with a ping sender (thanks Paho)
        }
    }

	@TargetApi(Build.VERSION_CODES.M)
	private void onDeviceIdleChanged() {
		if(powerManager.isDeviceIdleMode()) {
			Timber.v("idleMode: enabled");
		} else {
			Timber.v("idleMode: disabled");

		}
	}

	private void unregisterReceiver() {
		if(idleReceiver != null && receiverRegisterd)
			context.unregisterReceiver(idleReceiver);
	}


	private void registerReceiver() {
		IntentFilter filter = new IntentFilter();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);

			idleReceiver = new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					onDeviceIdleChanged();
				}
			};
			context.registerReceiver(idleReceiver, filter);
			receiverRegisterd = true;
		}

	}


	@Override
	public void probe() {
		Timber.d("mqttClient:%s, mqttClient.isConnected:%s, state:%s, reconnectHandlerEngaged:%s, pubPool.isRunning:%s", mqttClient!=null, mqttClient != null && mqttClient.isConnected(), getState(), reconnectHandler.hasStarted, pubPool.isRunning());

		if(error != null)
			Timber.e(error, "hasError");
	}

	class PingHandler implements MqttPingSender {
        static final String TAG = "PingHandler";

        private ClientComms comms;
		private WakeLock wakelock;

		private synchronized boolean releaseWakeLock() {
			if(wakelock != null && wakelock.isHeld()){
				Log.d(TAG, "Release lock ok(" + ServiceProxy.WAKELOCK_TAG_BROKER_PING + "):" + System.currentTimeMillis());

				wakelock.release();
				return true;
			}
			Log.d(TAG, "Release lock underlock or null (" + ServiceProxy.WAKELOCK_TAG_BROKER_PING + "):" + System.currentTimeMillis());
			return false;
		}

		private synchronized void initWakeLock() {
			if (wakelock == null) {
				wakelock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, ServiceProxy.WAKELOCK_TAG_BROKER_PING);
			}
		}

		private synchronized void acquireWakelock() {
			if(!wakelock.isHeld())
				wakelock.acquire();
		}

        public void ping(Intent intent) {
			Log.v(TAG, "sending");

			initWakeLock();

			acquireWakelock();

			if(comms == null) {
				Log.v(TAG, "comms is null, running doStart()");
				PendingIntent p = ServiceProxy.getBroadcastIntentForService(context, ServiceProxy.SERVICE_MESSAGE, RECEIVER_ACTION_RECONNECT, null);
				try {
					p.send();
				} catch (PendingIntent.CanceledException ignored) {

				} finally {
					Log.v(TAG, "releaseWakeLock 1");
					releaseWakeLock();
				}
				return;
			}


			IMqttToken token = comms.checkForActivity(new IMqttActionListener() {
				@Override
				public void onSuccess(IMqttToken asyncActionToken) {
					Log.d(TAG, "Success. Release lock(" + ServiceProxy.WAKELOCK_TAG_BROKER_PING + "):" + System.currentTimeMillis());
					Log.v(TAG, "releaseWakeLock 2 onSuccess");

					releaseWakeLock();
				}

				@Override
				public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
					Log.d(TAG, "Failure. Release lock(" + ServiceProxy.WAKELOCK_TAG_BROKER_PING + "):" + System.currentTimeMillis());
					Log.v(TAG, "releaseWakeLock 3 onFailure");

					releaseWakeLock();
				}
			});

		}

        public PingHandler(Context c) {
            if (c == null) {
                throw new IllegalArgumentException( "Neither service nor client can be null.");
            }
        }

        @Override
        public void init(ClientComms comms) {
            Log.v(TAG, "init " + this);
			this.comms = comms;
        }

        @Override
        public void start() {
            Log.v(TAG, "start " + this);
			if(comms != null)
	            schedule(comms.getKeepAlive());
        }

        @Override
        public void stop() {
            Log.v(TAG, "stop " + this);
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(ServiceProxy.ALARM_SERVICE);
            alarmManager.cancel(ServiceProxy.getBroadcastIntentForService(context, ServiceProxy.SERVICE_MESSAGE, ServiceDispatcherMqtt.RECEIVER_ACTION_PING, null));
			releaseWakeLock();
        }

		// Schedules a BroadcastIntent that will trigger a ping message when received.
		// It will be received by ServiceMessageMqtt.onStartCommand which recreates the service in case it has been stopped
		// onStartCommand will then deliver the intent to the ping(...) method if the service was alive or it will trigger a new connection attempt
        @Override
        public void schedule(long delayInMilliseconds) {

			long targetTstMs = System.currentTimeMillis() + delayInMilliseconds;
			AlarmManager alarmManager = (AlarmManager) context.getSystemService(ServiceProxy.ALARM_SERVICE);
			PendingIntent p = ServiceProxy.getBroadcastIntentForService(context, ServiceProxy.SERVICE_MESSAGE, ServiceDispatcherMqtt.RECEIVER_ACTION_PING, null);
			if (Build.VERSION.SDK_INT >= 19) {
				alarmManager.setExact(AlarmManager.RTC_WAKEUP, targetTstMs, p);
			} else {
				alarmManager.set(AlarmManager.RTC_WAKEUP, targetTstMs, p);
			}

			Log.v(TAG, "scheduled ping at tst " + (targetTstMs) +" (current: " + System.currentTimeMillis() +" /"+ delayInMilliseconds+ ")");

        }
    }

    class ReconnectHandler {
		private static final String TAG = "ReconnectHandler";
		private static final int BACKOFF_INTERVAL_MAX = 5;
		private int backoff = 0;

		private final Context context;
        private boolean hasStarted;


        public ReconnectHandler(Context context) {
            this.context = context;
        }




        public void stop() {
            Log.v(TAG, "stopping reconnect handler");
			backoff = 0;
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(ServiceProxy.ALARM_SERVICE);
            alarmManager.cancel(ServiceProxy.getBroadcastIntentForService(this.context, ServiceProxy.SERVICE_MESSAGE, RECEIVER_ACTION_RECONNECT, null));

            if (hasStarted) {
                hasStarted = false;
            }
        }

        public void schedule() {
			hasStarted = true;
			AlarmManager alarmManager = (AlarmManager) context.getSystemService(ServiceProxy.ALARM_SERVICE);
			long delayInMilliseconds;
			delayInMilliseconds =  (long)Math.pow(2, backoff) * TimeUnit.SECONDS.toMillis(30);

			Timber.v("scheduling reconnect delay:%s", delayInMilliseconds);

			PendingIntent p = ServiceProxy.getBroadcastIntentForService(this.context, ServiceProxy.SERVICE_MESSAGE, RECEIVER_ACTION_RECONNECT, null);
			if (Build.VERSION.SDK_INT >= 19) {
				alarmManager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayInMilliseconds, p);
			} else {
				alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayInMilliseconds, p);
			}

			if(backoff <= BACKOFF_INTERVAL_MAX)
				backoff++;
		}

	}

    private static final class CustomMemoryPersistence implements MqttClientPersistence {
        private static Hashtable data;

        public CustomMemoryPersistence(){

        }

        @Override
        public void open(String s, String s2) throws MqttPersistenceException {
            if(data == null) {
                data = new Hashtable();
            }
        }

		@SuppressWarnings("unused")
        private Integer getSize(){
            return data.size();
        }

        @Override
        public void close() throws MqttPersistenceException {

        }

        @Override
        public void put(String key, MqttPersistable persistable) throws MqttPersistenceException {
            data.put(key, persistable);
        }

        @Override
        public MqttPersistable get(String key) throws MqttPersistenceException {
            return (MqttPersistable)data.get(key);
        }

        @Override
        public void remove(String key) throws MqttPersistenceException {
            data.remove(key);
        }

        @Override
        public Enumeration keys() throws MqttPersistenceException {
            return data.keys();
        }

        @Override
        public void clear() throws MqttPersistenceException {
            data.clear();
        }

        @Override
        public boolean containsKey(String key) throws MqttPersistenceException {
            return data.containsKey(key);
        }



	}
}
