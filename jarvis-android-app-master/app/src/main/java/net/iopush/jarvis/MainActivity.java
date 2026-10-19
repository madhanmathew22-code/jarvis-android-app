package net.iopush.jarvis;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CODE_SPEECH_INPUT = 100;
    private static final String TAG = "Jarvis";

    private FloatingActionButton btnSpeak;
    private RecyclerView recyclerViewConversation;
    private ProgressBar pBarWaitingJArvis;
    private TextView hudStatus;
    private View hudCore;
    private View hudOuterRing;
    private View hudInnerRing;

    private final List<ConversationObject> jarvisConversationList = new ArrayList<>();
    private TextToSpeech ttsEngine;
    private RequestQueue requestQueue;
    private boolean muteLocalJarvis = false;
    private ObjectAnimator outerRotation;
    private ObjectAnimator innerRotation;
    private ObjectAnimator corePulse;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pBarWaitingJArvis = findViewById(R.id.pBarWaitingJArvis);
        recyclerViewConversation = findViewById(R.id.recyclerViewConversation);
        recyclerViewConversation.setLayoutManager(new LinearLayoutManager(this));
        jarvisConversationList.add(new ConversationObject("", "J.A.R.V.I.S online. Tap the microphone to begin."));
        recyclerViewConversation.setAdapter(new ConversationAdapter(jarvisConversationList));

        btnSpeak = findViewById(R.id.btnSpeak);
        btnSpeak.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                promptSpeechInput();
            }
        });

        hudStatus = findViewById(R.id.hudStatus);
        // HUD views are optional so this file remains compatible with layouts without them.
        int outerId = getResources().getIdentifier("hudOuterRing", "id", getPackageName());
        int innerId = getResources().getIdentifier("hudInnerRing", "id", getPackageName());
        int coreId = getResources().getIdentifier("hudCore", "id", getPackageName());
        if (outerId != 0) hudOuterRing = findViewById(outerId);
        if (innerId != 0) hudInnerRing = findViewById(innerId);
        if (coreId != 0) hudCore = findViewById(coreId);
        startHudAnimation();

        requestQueue = Volley.newRequestQueue(getApplicationContext());

        ttsEngine = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS && ttsEngine != null) {
                    int result = ttsEngine.setLanguage(Locale.getDefault());
                    if (result == TextToSpeech.LANG_MISSING_DATA ||
                            result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Default TTS language is unavailable.");
                    }
                } else {
                    Log.w(TAG, "Text-to-speech initialization failed.");
                }
            }
        });

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        muteLocalJarvis = prefs.getBoolean("muteLocalJarvis", false);
        boolean sttAtStart = prefs.getBoolean("sttAtStart", false);
        if (sttAtStart) {
            btnSpeak.postDelayed(new Runnable() {
                @Override public void run() {
                    promptSpeechInput();
                }
            }, 500);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        muteLocalJarvis = prefs.getBoolean("muteLocalJarvis", false);
    }

    private void promptSpeechInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to J.A.R.V.I.S.");
        try {
            startActivityForResult(intent, REQ_CODE_SPEECH_INPUT);
            setBusy(true, "LISTENING");
        } catch (ActivityNotFoundException e) {
            setBusy(false, "VOICE UNAVAILABLE");
            Toast.makeText(this, "Speech recognition is not available on this device.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CODE_SPEECH_INPUT) return;

        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> results =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String userText = results.get(0);
                jarvisConversationList.add(0, new ConversationObject("You", userText));
                recyclerViewConversation.getAdapter().notifyItemInserted(0);
                recyclerViewConversation.smoothScrollToPosition(0);
                askGemini(userText);
                return;
            }
        }
        setBusy(false, "READY");
    }

    private void askGemini(final String userText) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final String apiKey = prefs.getString("geminiApiKey", "").trim();
        if (apiKey.isEmpty()) {
            setBusy(false, "API KEY REQUIRED");
            Snackbar.make(findViewById(R.id.mainActivity),
                    "Add your Gemini API key in Settings first.", Snackbar.LENGTH_LONG)
                    .setAction("Settings", new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            startActivity(new Intent(MainActivity.this, MyPreferencesActivity.class));
                        }
                    }).show();
            return;
        }

        setBusy(true, "THINKING");
        final String endpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";

        JSONObject body = new JSONObject();
        try {
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("text", userText));

            JSONObject userContent = new JSONObject();
            userContent.put("role", "user");
            userContent.put("parts", parts);

            JSONArray contents = new JSONArray();
            contents.put(userContent);
            body.put("contents", contents);

            JSONObject systemPart = new JSONObject();
            systemPart.put("text",
                    "You are J.A.R.V.I.S., a helpful, calm, concise AI assistant. " +
                    "Address the user respectfully. Give clear practical answers. " +
                    "For voice responses, avoid excessive formatting and keep replies natural.");

            JSONObject systemInstruction = new JSONObject();
            JSONArray systemParts = new JSONArray();
            systemParts.put(systemPart);
            systemInstruction.put("parts", systemParts);
            body.put("systemInstruction", systemInstruction);

            JSONObject generationConfig = new JSONObject();
            generationConfig.put("temperature", 0.7);
            generationConfig.put("maxOutputTokens", 700);
            body.put("generationConfig", generationConfig);
        } catch (JSONException e) {
            setBusy(false, "REQUEST ERROR");
            showMessage("Could not prepare the AI request.");
            return;
        }

        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST, endpoint, body,
                new Response.Listener<JSONObject>() {
                    @Override public void onResponse(JSONObject response) {
                        String answer = extractAnswer(response);
                        if (answer.isEmpty()) {
                            setBusy(false, "NO RESPONSE");
                            showMessage("Gemini returned an empty response. Please try again.");
                            return;
                        }
                        jarvisConversationList.add(0, new ConversationObject("J.A.R.V.I.S", answer));
                        recyclerViewConversation.getAdapter().notifyItemInserted(0);
                        recyclerViewConversation.smoothScrollToPosition(0);
                        setBusy(false, "ONLINE");
                        if (!muteLocalJarvis && ttsEngine != null) {
                            if (android.os.Build.VERSION.SDK_INT >= 21) {
                                ttsEngine.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply");
                            } else {
                                //noinspection deprecation
                                ttsEngine.speak(answer, TextToSpeech.QUEUE_FLUSH, null);
                            }
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override public void onErrorResponse(VolleyError error) {
                        setBusy(false, "CONNECTION ERROR");
                        String message = "Could not reach Gemini. Check your internet connection.";
                        if (error.networkResponse != null) {
                            int code = error.networkResponse.statusCode;
                            if (code == 400 || code == 403) {
                                message = "Gemini rejected the request or API key. Check your key and API access in Settings.";
                            } else if (code == 429) {
                                message = "Gemini request limit reached. Please try again later.";
                            } else if (code >= 500) {
                                message = "Gemini service is temporarily unavailable. Please try again.";
                            }
                            Log.e(TAG, "Gemini HTTP status: " + code);
                        } else {
                            Log.e(TAG, "Gemini network error", error);
                        }
                        showMessage(message);
                    }
                }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json; charset=utf-8");
                headers.put("x-goog-api-key", apiKey);
                return headers;
            }
        };
        request.setShouldCache(false);
        request.setRetryPolicy(new com.android.volley.DefaultRetryPolicy(
                30000, 1, 1.0f));
        requestQueue.add(request);
    }

    private String extractAnswer(JSONObject response) {
        try {
            JSONArray candidates = response.getJSONArray("candidates");
            if (candidates.length() == 0) return "";
            JSONObject content = candidates.getJSONObject(0).getJSONObject("content");
            JSONArray parts = content.getJSONArray("parts");
            StringBuilder answer = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                if (part.has("text")) {
                    if (answer.length() > 0) answer.append("\n");
                    answer.append(part.getString("text"));
                }
            }
            return answer.toString().trim();
        } catch (JSONException e) {
            Log.e(TAG, "Could not parse Gemini response", e);
            return "";
        }
    }

    private void showMessage(String message) {
        View root = findViewById(R.id.mainActivity);
        if (root != null) {
            Snackbar.make(root, message, Snackbar.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private void setBusy(boolean busy, String status) {
        if (pBarWaitingJArvis != null) {
            pBarWaitingJArvis.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        if (btnSpeak != null) {
            btnSpeak.setVisibility(busy ? View.INVISIBLE : View.VISIBLE);
        }
        if (hudStatus != null) hudStatus.setText(status);
    }

    private void startHudAnimation() {
        if (hudOuterRing != null) {
            outerRotation = ObjectAnimator.ofFloat(hudOuterRing, "rotation", 0f, 360f);
            outerRotation.setDuration(14000);
            outerRotation.setRepeatCount(ValueAnimator.INFINITE);
            outerRotation.setInterpolator(new LinearInterpolator());
            outerRotation.start();
        }
        if (hudInnerRing != null) {
            innerRotation = ObjectAnimator.ofFloat(hudInnerRing, "rotation", 360f, 0f);
            innerRotation.setDuration(9000);
            innerRotation.setRepeatCount(ValueAnimator.INFINITE);
            innerRotation.setInterpolator(new LinearInterpolator());
            innerRotation.start();
        }
        if (hudCore != null) {
            corePulse = ObjectAnimator.ofFloat(hudCore, "alpha", 0.65f, 1f);
            corePulse.setDuration(1100);
            corePulse.setRepeatMode(ValueAnimator.REVERSE);
            corePulse.setRepeatCount(ValueAnimator.INFINITE);
            corePulse.start();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, MyPreferencesActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        if (outerRotation != null) outerRotation.cancel();
        if (innerRotation != null) innerRotation.cancel();
        if (corePulse != null) corePulse.cancel();
        if (ttsEngine != null) {
            ttsEngine.stop();
            ttsEngine.shutdown();
        }
        super.onDestroy();
    }
}
