package com.planet;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import com.planet.binding.PlatformBinding;
import com.planet.binding.crypto.AndroidCryptoProvider;
import com.planet.computers.ComputerManagerListener;
import com.planet.computers.ComputerManagerService;
import com.planet.grid.PcGridAdapter;
import com.planet.grid.assets.DiskAssetLoader;
import com.planet.nvstream.http.ComputerDetails;
import com.planet.nvstream.http.NvApp;
import com.planet.nvstream.http.NvHTTP;
import com.planet.nvstream.http.PairingManager;
import com.planet.nvstream.http.PairingManager.PairState;
import com.planet.nvstream.jni.MoonBridge;
import com.planet.nvstream.wol.WakeOnLanSender;
import com.planet.preferences.AddComputerManually;
import com.planet.preferences.GlPreferences;
import com.planet.preferences.PreferenceConfiguration;
import com.planet.preferences.StreamSettings;
import com.planet.ui.AdapterFragment;
import com.planet.ui.AdapterFragmentCallbacks;
import com.planet.utils.Dialog;
import com.planet.utils.HelpLauncher;
import com.planet.utils.ServerHelper;
import com.planet.utils.ShortcutHelper;
import com.planet.utils.SpinnerDialog;
import com.planet.utils.UiHelper;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParserException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class PcView extends Activity implements AdapterFragmentCallbacks {
    TextView logout;
    ImageButton refresh;
    private FirebaseAuth mAuth;
    private RelativeLayout noPcFoundLayout;
    private PcGridAdapter pcGridAdapter;
    private ShortcutHelper shortcutHelper;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;
    private final LinkedBlockingQueue<String> computersToAdd = new LinkedBlockingQueue<>();
    List<String> list = new ArrayList<>();
    HashMap<String, String> timeMap=new HashMap<String, String>();
    private Thread addThread;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);




            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start updates

                    //startComputerUpdates();
                    startAddThread();

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            joinAddThread();
            managerBinder = null;
        }
    };
    String token_="221a0e54fbfeb04fe24081edcb3daff9e3ba569ed4983f12cff594a67d07e72b9513de477511166d859bd8e9d85c4001378a2d9cafd18914fa04d1aa6243bec9db627628070af4e5d5aef584123a4cf293ab0ce1550bdc2e14ab746947d8fe82569094b9df666cab28d95bfc49a69a2f51d979a50762587ff581d779370088c2";
    JSONArray array;
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Only reinitialize views if completeOnCreate() was called
        // before this callback. If it was not, completeOnCreate() will
        // handle initializing views with the config change accounted for.
        // This is not prone to races because both callbacks are invoked
        // in the main thread.
        if (completeOnCreateCalled) {
            // Reinitialize views just in case orientation changed

            initializeViews();
        }
    }

    private final static int PAIR_ID = 2;
    private final static int UNPAIR_ID = 3;
    private final static int WOL_ID = 4;
    private final static int DELETE_ID = 5;
    private final static int RESUME_ID = 6;
    private final static int QUIT_ID = 7;
    private final static int VIEW_DETAILS_ID = 8;
    private final static int FULL_APP_LIST_ID = 9;
    private final static int TEST_NETWORK_ID = 10;

    private void initializeViews() {
        setContentView(R.layout.activity_pc_view);

        UiHelper.notifyNewRootView(this);

        // Set default preferences if we've never been run
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Set the correct layout for the PC grid
        pcGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

        // Setup the list view
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        ImageButton addComputerButton = findViewById(R.id.manuallyAddPc);
        logout=findViewById(R.id.logout_main);
        refresh=findViewById(R.id.refresh_btn);

        mAuth = FirebaseAuth.getInstance();

        FirebaseUser currentUser = mAuth.getCurrentUser();
        assert currentUser != null;

        settingsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PcView.this, StreamSettings.class));
            }
        });
        addComputerButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(PcView.this, AddComputerManually.class);
                startActivity(i);
            }
        });
        logout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                logout();
            }
        });

        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //logout();
                refresh();
            }
        });

        // Amazon review didn't like the help button because the wiki was not entirely
        // navigable via the Fire TV remote (though the relevant parts were). Let's hide
        // it on Fire TV.

        getFragmentManager().beginTransaction()
            .replace(R.id.pcFragmentContainer, new AdapterFragment())
            .commitAllowingStateLoss();

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout);
        if (pcGridAdapter.getCount() == 0) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
        }
        else {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        pcGridAdapter.notifyDataSetChanged();

    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        // Create a GLSurfaceView to fetch GLRenderer unless we have
        // a cached result already.
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    // Save the GLRenderer string so we don't need to do this next time
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();

                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            completeOnCreate();
                        }
                    });
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        }
        else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }
    }



    private void completeOnCreate() {
        completeOnCreateCalled = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        getTimer();




    }

    private void startComputerUpdates() {

        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            managerBinder.startPolling(new ComputerManagerListener() {
                @Override
                public void notifyComputerUpdated(final ComputerDetails details) {
                    if (!freezeUpdates) {
                        PcView.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateComputer(details);
                            }
                        });
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;

            managerBinder.stopPolling();

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
        }
    }

     void logout(){
        FirebaseAuth.getInstance().signOut();

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();

        GoogleSignIn.getClient(getApplicationContext(), gso).signOut();
        Toast.makeText(getApplicationContext(),"Logout successfully",Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(getApplicationContext(), LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }
    @Override
    public void onDestroy() {
        super.onDestroy();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        startComputerUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        stopComputerUpdates(false);

        // Call superclass
        super.onCreateContextMenu(menu, v, menuInfo);
                
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;


        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);

        // Add a header with PC status details
        menu.clearHeader();
        String headerTitle = computer.details.name + " - ";
        switch (computer.details.state)
        {
            case ONLINE:
                headerTitle += getResources().getString(R.string.pcview_menu_header_online);
                break;
            case OFFLINE:
                menu.setHeaderIcon(R.drawable.ic_pc_offline);
                headerTitle += getResources().getString(R.string.pcview_menu_header_offline);
                break;
            case UNKNOWN:
                headerTitle += getResources().getString(R.string.pcview_menu_header_unknown);
                break;
        }

        menu.setHeaderTitle(headerTitle);

        // Inflate the context menu
        if (computer.details.state == ComputerDetails.State.OFFLINE ||
            computer.details.state == ComputerDetails.State.UNKNOWN) {
            menu.add(Menu.NONE, WOL_ID, 1, getResources().getString(R.string.pcview_menu_send_wol));
        }
        else if (computer.details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID, 1, getResources().getString(R.string.pcview_menu_pair_pc));
        }
        else {
            if (computer.details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }

            menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, getResources().getString(R.string.pcview_menu_app_list));
        }
        menu.add(Menu.NONE, DELETE_ID, 5, getResources().getString(R.string.pcview_menu_delete_pc));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        // For some reason, this gets called again _after_ onPause() is called on this activity.
        // startComputerUpdates() manages this and won't actual start polling until the activity
        // returns to the foreground.
        startComputerUpdates();
    }

    private void doPair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE ||
                ServerHelper.getCurrentAddressFromComputer(computer) == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (computer.runningGameId != 0) {
            Toast.makeText(PcView.this, getResources().getString(R.string.pair_pc_ingame), Toast.LENGTH_LONG).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                boolean success = false;
                try {
                    // Stop updates and wait while pairing
                    stopComputerUpdates(true);

                    Log.d("connect","connect_____________1");
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            managerBinder.getUniqueId(),
                            computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    Log.d("connect","connect_____________2");
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        // Don't display any toast, but open the app list
                        Log.d("connect","connect_____________3");

                        message = null;
                        success = true;
                    }
                    else {
                        Log.d("connect","connect_____________4");
                        final String pinStr = "0000";

                        // Spin the dialog off in a thread because it blocks
                        Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                                getResources().getString(R.string.pair_pairing_msg)+" "+pinStr, false);

                        PairingManager pm = httpConn.getPairingManager();

                        PairState pairState = pm.pair(httpConn.getServerInfo(), pinStr);
                        if (pairState == PairState.PIN_WRONG) {
                            Log.d("connect","connect_____________5");
                            message = getResources().getString(R.string.pair_incorrect_pin);
                        }
                        else if (pairState == PairState.FAILED) {
                            Log.d("connect","connect_____________6");
                            message = getResources().getString(R.string.pair_fail);
                        }
                        else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                            Log.d("connect","connect_____________7");
                            message = getResources().getString(R.string.pair_already_in_progress);
                        }
                        else if (pairState == PairState.PAIRED) {

                            Log.d("connect","connect_____________8");
                            // Just navigate to the app view without displaying a toast
                            message = null;
                            success = true;

                            // Pin this certificate for later HTTPS use
                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();

                            // Invalidate reachability information after pairing to force
                            // a refresh before reading pair state again
                            managerBinder.invalidateStateForComputer(computer.uuid);
                        }
                        else {
                            Log.d("connect","connect_____________9");
                            // Should be no other values
                            message = null;
                        }
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                    message = e.getMessage();
                }

                Dialog.closeDialogs();

                final String toastMessage = message;
                final boolean toastSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (toastMessage != null) {
                            Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                        }

                        if (toastSuccess) {
                            // Open the app list after a successful pairing attempt
                            String timestamp=String.valueOf(System.currentTimeMillis());
                            postTimer(computer.manualAddress,timestamp);
                            Log.d("connect","connect_____________10  "+computer.manualAddress);
                            //computer.time=String.valueOf(System.currentTimeMillis());

                            updateComputer(computer);
                            doAppList(computer, true, false);
                        }
                        else {
                            Log.d("connect","connect_____________11");
                            // Start polling again if we're still in the foreground
                            startComputerUpdates();
                        }
                    }
                });
            }
        }).start();
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_pc_online), Toast.LENGTH_SHORT).show();
            return;
        }

        if (computer.macAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_no_mac), Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    WakeOnLanSender.sendWolPacket(computer);
                    message = getResources().getString(R.string.wol_waking_msg);
                } catch (IOException e) {
                    message = getResources().getString(R.string.wol_fail);
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE ||
                ServerHelper.getCurrentAddressFromComputer(computer) == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            managerBinder.getUniqueId(),
                            computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairingManager.PairState.PAIRED) {
                        httpConn.unpair();
                        if (httpConn.getPairState() == PairingManager.PairState.NOT_PAIRED) {
                            message = getResources().getString(R.string.unpair_success);
                        }
                        else {
                            message = getResources().getString(R.string.unpair_fail);
                        }
                    }
                    else {
                        message = getResources().getString(R.string.unpair_error);
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    message = e.getMessage();
                    e.printStackTrace();
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        boolean found=false;
        String id="";
        String timestamp="";
        for(int i=0;i<array.length();i++){
            try {
                JSONObject obj=array.getJSONObject(i);

                if(TextUtils.equals(computer.manualAddress,obj.getJSONObject("attributes").getString("ip"))){
                    id=obj.getString("id");
                    timestamp=obj.getJSONObject("attributes").getString("timestamp");
                    found=true;
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        if(!found){
            timestamp="";
        }


        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.TIME, computer.time);
        i.putExtra(AppView.TIMESTAMP, timestamp);
        i.putExtra(AppView.ID, id);
        i.putExtra(AppView.IP, computer.manualAddress);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);
        startActivity(i);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);
        switch (item.getItemId()) {
            case PAIR_ID:
                doPair(computer.details);
                return true;

            case UNPAIR_ID:
                doUnpair(computer.details);
                return true;

            case WOL_ID:
                doWakeOnLan(computer.details);
                return true;

            case DELETE_ID:
                removeComputer(computer.details); // through thisokpahele custom ek array bao ok abhi karo ge ki baad me ? work
                return true;
//                if (ActivityManager.isUserAMonkey()) {
//                    LimeLog.info("Ignoring delete PC request from monkey");
//                    return true;
//                }
//                UiHelper.displayDeletePcConfirmationDialog(this, computer.details, new Runnable() {
//                    @Override
//                    public void run() {
////                        if (managerBinder == null) {
////                            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
////                            return;
////                        }
////                        removeComputer(computer.details);
////                        doUnpair(computer.details);
//                        doUnpair(computer.details);
//                    }
//                }, null);


            case FULL_APP_LIST_ID:
                doAppList(computer.details, false, true);
                return true;

            case RESUME_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                ServerHelper.doStart(this, new NvApp("app", computer.details.runningGameId, false), computer.details, managerBinder);
                return true;

            case QUIT_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doQuit(PcView.this, computer.details,
                                new NvApp("app", 0, false), managerBinder, null);
                    }
                }, null);
                return true;

            case VIEW_DETAILS_ID:
                Dialog.displayDialog(PcView.this, getResources().getString(R.string.title_details), computer.details.toString(), false);
                return true;

            case TEST_NETWORK_ID:
                ServerHelper.doNetworkTest(PcView.this);
                return true;

            default:
                return super.onContextItemSelected(item);
        }
    }
    
    private void removeComputer(ComputerDetails details) {

        managerBinder.removeComputer(details);

        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);

        // Delete hidden games preference value
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();


        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            if (details.equals(computer.details)) {
                // Disable or delete shortcuts referencing this PC
                shortcutHelper.disableComputerShortcut(details,
                        getResources().getString(R.string.scut_deleted_pc));

                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();

                if (pcGridAdapter.getCount() == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }

                break;
            }
        }
    }
    
    private void updateComputer(ComputerDetails details) {

        ComputerObject existingEntry = null;

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            // Check if this is the same computer
            if (details.uuid.equals(computer.details.uuid)) {
                existingEntry = computer;
                break;
            }
        }

        // Add a launcher shortcut for this PC
        if (details.pairState == PairState.PAIRED) {
            shortcutHelper.createAppViewShortcutForOnlineHost(details);
        }

        if (existingEntry != null) {
            // Replace the information in the existing entry
            existingEntry.details = details;

        }
        else {
            // Add a new entry
            pcGridAdapter.addComputer(new ComputerObject(details));


            // Remove the "Discovery in progress" view
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        // Notify the view that the data has changed
        pcGridAdapter.notifyDataSetChanged();
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return R.layout.pc_grid_view;
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {

        listView.setAdapter(pcGridAdapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(pos);
                if (computer.details.state == ComputerDetails.State.UNKNOWN ||
                    computer.details.state == ComputerDetails.State.OFFLINE) {
                    // Open the context menu if a PC is offline or refreshing
                    openContextMenu(arg1);
                } else if (computer.details.pairState != PairState.PAIRED) {
                    // Pair an unpaired machine by default
                    doPair(computer.details);
                } else {
                    doAppList(computer.details, false, false);
                }
            }
        });
        UiHelper.applyStatusBarPadding(listView);
        registerForContextMenu(listView);
    }

    public static class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
    }

    void getIP(){
        RequestQueue vQueue = Volley.newRequestQueue(getApplicationContext());
        vQueue.getCache().clear();
        String vurl =  "https://raw.githubusercontent.com/sudhanshugairola/api/main/ipaddress";
        JsonArrayRequest vrequest = new JsonArrayRequest(Request.Method.GET, vurl, null, new Response.Listener<JSONArray>() {

            @Override
            public void onResponse(JSONArray response) {


                for(int i=0;i<response.length();i++){
                    try {
                        JSONObject obj=response.getJSONObject(i);
                        Log.d("response",obj.getString("ip"));
                        if(obj.getString("ip").length()!=0){
                            list.add(obj.getString("ip"));

                            timeMap.put(obj.getString("ip"),obj.getString("Time"));

                        }

                    }catch (JSONException e){
                        e.printStackTrace();
                    }

                }
                deleteAll();
            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.e("error", error.toString());
            }
        });

        vrequest.setShouldCache(false);
        vQueue.add(vrequest);
    }

    private void custom(String hostAddress) {
        Log.d("ip","_______________  "+hostAddress);
        computersToAdd.add(hostAddress);
    }

    private boolean isWrongSubnetSiteLocalAddress(String address) {
        try {
            InetAddress targetAddress = InetAddress.getByName(address);
            if (!(targetAddress instanceof Inet4Address) || !targetAddress.isSiteLocalAddress()) {
                return false;
            }

            // We have a site-local address. Look for a matching local interface.
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                    if (!(addr.getAddress() instanceof Inet4Address) || !addr.getAddress().isSiteLocalAddress()) {
                        // Skip non-site-local or non-IPv4 addresses
                        continue;
                    }

                    byte[] targetAddrBytes = targetAddress.getAddress();
                    byte[] ifaceAddrBytes = addr.getAddress().getAddress();

                    // Compare prefix to ensure it's the same
                    boolean addressMatches = true;
                    for (int i = 0; i < addr.getNetworkPrefixLength(); i++) {
                        if ((ifaceAddrBytes[i / 8] & (1 << (i % 8))) != (targetAddrBytes[i / 8] & (1 << (i % 8)))) {
                            addressMatches = false;
                            break;
                        }
                    }

                    if (addressMatches) {
                        return false;
                    }
                }
            }

            // Couldn't find a matching interface
            return true;
        } catch (Exception e) {
            // Catch all exceptions because some broken Android devices
            // will throw an NPE from inside getNetworkInterfaces().
            e.printStackTrace();
            return false;
        }
    }
    private URI parseRawUserInputToUri(String rawUserInput) {
        Log.d("add ip ____",rawUserInput);
        try {
            // Try adding a scheme and parsing the remaining input.
            // This handles input like 127.0.0.1:47989, [::1], [::1]:47989, and 127.0.0.1.
            URI uri = new URI("moonlight://" + rawUserInput);
            if (uri.getHost() != null && !uri.getHost().isEmpty()) {
                return uri;
            }
        } catch (URISyntaxException ignored) {}

        try {
            // Attempt to escape the input as an IPv6 literal.
            // This handles input like ::1.
            URI uri = new URI("moonlight://[" + rawUserInput + "]");
            if (uri.getHost() != null && !uri.getHost().isEmpty()) {
                return uri;
            }
        } catch (URISyntaxException ignored) {}

        return null;
    }

    private void doAddPc(String host) {
        Log.d("add do ____","doadddddddddddddddddddddddddd");
        boolean wrongSiteLocal = false;
        boolean success;
        int portTestResult;

        SpinnerDialog dialog = SpinnerDialog.displayDialog(this, "Add Gaming PC",
                getResources().getString(R.string.msg_add_pc), false);

        try {
            URI uri = parseRawUserInputToUri(host);
            ComputerDetails details = new ComputerDetails();
            details.manualAddress = host;

            if (uri != null && uri.getHost() != null && !uri.getHost().isEmpty()) {
                String host = uri.getHost();
                int port = uri.getPort();

                // If a port was not specified, use the default
                if (port == -1) {
                    port = NvHTTP.DEFAULT_HTTP_PORT;
                }

                details.manualAddress = new ComputerDetails.AddressTuple(host, port);
                success = managerBinder.addComputerBlocking(details);
                if (!success){
                    wrongSiteLocal = isWrongSubnetSiteLocalAddress(host);
                }
            } else {
                // Invalid user input
                success = false;

            }

            if(timeMap.get(host)!=null){
                details.time = timeMap.get(host);
            }else{
                details.time = "";
            }

            success = managerBinder.addComputerBlocking(details);
        } catch (IllegalArgumentException e) {
            // This can be thrown from OkHttp if the host fails to canonicalize to a valid name.
            // https://github.com/square/okhttp/blob/okhttp_27/okhttp/src/main/java/com/squareup/okhttp/HttpUrl.java#L705
            e.printStackTrace();
            success = false;
        }
        if (!success){
            wrongSiteLocal = isWrongSubnetSiteLocalAddress(host);
        }
        if (!success && !wrongSiteLocal) {
            // Run the test before dismissing the spinner because it can take a few seconds.
            portTestResult = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER, 443,
                    MoonBridge.ML_PORT_FLAG_TCP_47984 | MoonBridge.ML_PORT_FLAG_TCP_47989);
        } else {
            // Don't bother with the test if we succeeded or the IP address was bogus
            portTestResult = MoonBridge.ML_TEST_RESULT_INCONCLUSIVE;
        }

        dialog.dismiss();

        if (wrongSiteLocal) {
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title), getResources().getString(R.string.addpc_wrong_sitelocal), false);
            if(list.size()!=0){
                custom(list.get(0));
                list.remove(0);
            }
        }
        else if (!success) {
            String dialogText;
            if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0)  {
                dialogText = getResources().getString(R.string.nettest_text_blocked);
            }
            else {
                dialogText = getResources().getString(R.string.addpc_fail);
            }
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title), dialogText, false);
            if(list.size()!=0){
                custom(list.get(0));
                list.remove(0);
            }
        }
        else {
            PcView.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    //Toast.makeText(PcView.this, getResources().getString(R.string.addpc_success), Toast.LENGTH_LONG).show();

                    Log.d("last","complete___________");
                    if(list.size()!=0){
                        custom(list.get(0));
                        list.remove(0);
                    }else {
                        startComputerUpdates();

                        Dialog.closeDialogs();
                        SpinnerDialog.closeDialogs(PcView.this);
                    }


                }
            });
        }

    }

    private void startAddThread() {
        addThread = new Thread() {
            @Override
            public void run() {
                while (!isInterrupted()) {
                    String computer;

                    try {
                        computer = computersToAdd.take();


                    } catch (InterruptedException e) {
                        return;
                    }

                    doAddPc(computer);
                }
            }
        };
        addThread.setName("UI - PcView");
        addThread.start();
    }

    private void joinAddThread() {
        if (addThread != null) {
            addThread.interrupt();

            try {
                addThread.join();
            } catch (InterruptedException ignored) {}

            addThread = null;
        }
    }

    void deleteAll(){

        int count=pcGridAdapter.getCount();
        int n=0;
        for (int i = 0; i < count; i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(n);
            Log.d("list size","containt________"+computer.details.manualAddress+"__"+String.valueOf(list.size()));
            if(list.contains(computer.details.manualAddress)){
                Log.d("list","containt__________");
                list.remove(list.indexOf(computer.details.manualAddress));
                n++;
            }else {
                  managerBinder.removeComputer(computer.details);

            new DiskAssetLoader(this).deleteAssetsForComputer(computer.details.uuid);

            // Delete hidden games preference value
            getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                    .edit()
                    .remove(computer.details.uuid)
                    .apply();

            // Disable or delete shortcuts referencing this PC
                shortcutHelper.disableComputerShortcut(computer.details,
                        getResources().getString(R.string.scut_deleted_pc));

                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();

                if (pcGridAdapter.getCount() == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }
            }

        }

        Log.d("ip",String.valueOf(list.size()));
        if(list.size()!=0){
            custom(list.get(0));
            list.remove(0);
        }

    }


    void getTimer(){
        RequestQueue vQueue = Volley.newRequestQueue(getApplicationContext());
        vQueue.getCache().clear();
        String vurl =  "http://52.172.233.242:1337/api/lists";
        JsonObjectRequest vrequest = new JsonObjectRequest(Request.Method.GET, vurl, null, new Response.Listener<JSONObject>() {

            @Override
            public void onResponse(JSONObject response) {

                Log.d("get response","outerrrrrrrrrrrrrrrrrrr");

                try {
                    array=response.getJSONArray("data");
                    pcGridAdapter = new PcGridAdapter(PcView.this, PreferenceConfiguration.readPreferences(PcView.this),response.getJSONArray("data"));
                    initializeViews();

                    final Handler handler = new Handler();
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            getIP();
                        }
                    }, 500);
                } catch (JSONException e) {
                    e.printStackTrace();
                }



            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.e("error", error.toString());
            }
        }){
            @Override
            protected Map<String, String> getParams() throws AuthFailureError {
                return super.getParams();
            }
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {

                Map<String, String> headers = new HashMap<>();
                String token="Bearer "+token_;
                headers.put("Content-Type", "application/json");
                headers.put("Authorization", token);
                return headers;
            }
        };

        vrequest.setShouldCache(false);
        vQueue.add(vrequest);
    }

    void postTimer(String ip, String timeStamp){

        try {
            JSONObject obj=new JSONObject();

            JSONObject dataObj=new JSONObject();
            dataObj.put("ip",ip);
            dataObj.put("timestamp",timeStamp);

            obj.put("data",dataObj);
            RequestQueue vQueue = Volley.newRequestQueue(getApplicationContext());
            vQueue.getCache().clear();
            String vurl =  "http://52.172.233.242:1337/api/lists";
            JsonObjectRequest vrequest = new JsonObjectRequest(Request.Method.POST, vurl, obj, new Response.Listener<JSONObject>() {

                @Override
                public void onResponse(JSONObject response) {

                    Log.d("get response",response.toString());
                }
            }, new Response.ErrorListener() {
                @Override
                public void onErrorResponse(VolleyError error) {
                    Log.e("error", error.toString());
                }
            }){
                @Override
                protected Map<String, String> getParams() throws AuthFailureError {
                    return super.getParams();
                }
                @Override
                public Map<String, String> getHeaders() throws AuthFailureError {

                    Map<String, String> headers = new HashMap<>();
                    String token="Bearer "+token_;
                    headers.put("Content-Type", "application/json");
                    headers.put("Authorization", token);
                    return headers;
                }
            };

            vrequest.setShouldCache(false);
            vQueue.add(vrequest);

        }catch (JSONException e){

        }

    }

    void putTimer(String id,String ip, String timeStamp){

        try {
            JSONObject obj=new JSONObject();

            JSONObject dataObj=new JSONObject();
            dataObj.put("ip",ip);
            dataObj.put("timestamp",timeStamp);

            obj.put("data",dataObj);
            RequestQueue vQueue = Volley.newRequestQueue(getApplicationContext());
            vQueue.getCache().clear();
            String vurl =  "http://52.172.233.242:1337/api/lists/"+id;
            JsonObjectRequest vrequest = new JsonObjectRequest(Request.Method.PUT, vurl, obj, new Response.Listener<JSONObject>() {

                @Override
                public void onResponse(JSONObject response) {

                    Log.d("get response",response.toString());
                }
            }, new Response.ErrorListener() {
                @Override
                public void onErrorResponse(VolleyError error) {
                    Log.e("error", error.toString());
                }
            }){
                @Override
                protected Map<String, String> getParams() throws AuthFailureError {
                    return super.getParams();
                }
                @Override
                public Map<String, String> getHeaders() throws AuthFailureError {

                    Map<String, String> headers = new HashMap<>();
                    String token="Bearer "+token_;
                    headers.put("Content-Type", "application/json");
                    headers.put("Authorization", token);
                    return headers;
                }
            };

            vrequest.setShouldCache(false);
            vQueue.add(vrequest);

        }catch (JSONException e){

        }

    }

    void refresh(){

        RequestQueue vQueue = Volley.newRequestQueue(getApplicationContext());
        vQueue.getCache().clear();
        String vurl =  "http://52.172.233.242:1337/api/lists";
        JsonObjectRequest vrequest = new JsonObjectRequest(Request.Method.GET, vurl, null, new Response.Listener<JSONObject>() {

            @Override
            public void onResponse(JSONObject response) {

                Log.d("get response","outerrrrrrrrrrrrrrrrrrr   "+String.valueOf(System.currentTimeMillis()));

                try {
                    array=response.getJSONArray("data");
                    int count=pcGridAdapter.getCount();
                    for (int i = 0; i < count; i++) {
                        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(0);
                        Log.d("list size","containt________"+computer.details.manualAddress+"__"+String.valueOf(count));
                        pcGridAdapter.removeComputer(computer);

                    }
                    pcGridAdapter.notifyDataSetChanged();

                    //pcGridAdapter = new PcGridAdapter(PcView.this, PreferenceConfiguration.readPreferences(PcView.this),response.getJSONArray("data"));



                } catch (JSONException e) {
                    e.printStackTrace();
                }



            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.e("error", error.toString());
            }
        }){
            @Override
            protected Map<String, String> getParams() throws AuthFailureError {
                return super.getParams();
            }
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {

                Map<String, String> headers = new HashMap<>();
                String token="Bearer "+token_;
                headers.put("Content-Type", "application/json");
                headers.put("Authorization", token);
                return headers;
            }
        };

        vrequest.setShouldCache(false);
        vQueue.add(vrequest);
    }


}
