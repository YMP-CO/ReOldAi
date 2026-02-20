package com.ymp.reold.ai;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.SpannableString;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {
    //Наше основное активити.
    private static final String TAG = "MainActivity";
    private static final int SPEECH_INPUT_REQUEST_CODE = 100;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE = 1; // Для API < 30
    private static final int MANAGE_EXTERNAL_STORAGE_REQUEST_CODE = 2; // Для API >= 30

    private static final String PREFS_NAME = "AIPrefs";
    private static final String API_KEY_PREF = "ai_api_key";
    private static final String AI_MODEL_PREF = "ai_model";
    private static final String TEMPERATURE_PREF = "temperature";
    private static final String TOP_K_PREF = "top_k";
    private static final String TOP_P_PREF = "top_p";
    private static final String CUSTOM_MODEL_PREF = "custom_model"; 
    private static final String CHAT_HISTORY_LIST_PREF = "chat_history_list";
    private static final String WARNING_DIALOG_PREFS = "WarningDialogPrefs";
    private static final String HAS_WARNING_DIALOG_SHOWN = "hasWarningDialogShown";


    private EditText promptEditText;
    private ImageButton sendButton;
    private ImageButton micButton;
    private ListView chatListView;
    private ChatMessageAdapter chatAdapter;
    private ArrayList<ChatMessage> chatMessages;
    private TextView welcomeMessageTextView;
    private String aiApiKey;
    private String selectedAiModel;
    private String customModel; 
    private float temperature;
    private int topK;
    private float topP;
    private TextToSpeech tts;
    private boolean isVoiceInputActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        promptEditText = findViewById(R.id.prompt_edit_text);
        sendButton = findViewById(R.id.send_button);
        micButton = findViewById(R.id.mic_button);
        chatListView = findViewById(R.id.chat_list_view);
        welcomeMessageTextView = findViewById(R.id.welcome_message_text_view);

        chatMessages = new ArrayList<>();
        chatAdapter = new ChatMessageAdapter(this, chatMessages, this);
        chatListView.setAdapter(chatAdapter);

        loadSettings();

        if (TextUtils.isEmpty(aiApiKey)) {
            showApiKeyDialog(true);
        }

        SharedPreferences warningPrefs = getSharedPreferences(WARNING_DIALOG_PREFS, Context.MODE_PRIVATE);
        boolean hasWarningDialogBeenShown = warningPrefs.getBoolean(HAS_WARNING_DIALOG_SHOWN, false);

        if (!hasWarningDialogBeenShown) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Warning!");
            builder.setMessage("• Gemini API is provided by Google. Use of the API is subject to the Google API Terms of Use and Google's Privacy Policy.\n" +
                    "\n" +
                    "• Chat messages are sent to the Google API to generate responses.\n" +
                    "\n" +
                    "• Your API key is stored only on your device.\n" +
                    "\n" +
                    "Links:\n" +
                    "https://developers.google.com/terms\n" +
                    "https://policies.google.com/privacy\n" +
                    "https://ai.google.dev/gemini-api/terms\n" +
                    "\n" +
                    "This dialog will only be shown once.");
            builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    SharedPreferences.Editor editor = warningPrefs.edit();
                    editor.putBoolean(HAS_WARNING_DIALOG_SHOWN, true);
                    editor.apply();
                    dialog.dismiss();
                }
            });
            builder.setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
        }

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isVoiceInputActive = false; // Reset for text input
                sendMessage();
            }
        });

        micButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startVoiceInput();
            }
        });

        // TextToSpeech
        tts = new TextToSpeech(this, this);

        if (getIntent() != null && getIntent().hasExtra(ChatHistoryActivity.EXTRA_CHAT_FILE_PATH)) {
            String filePath = getIntent().getStringExtra(ChatHistoryActivity.EXTRA_CHAT_FILE_PATH);
            loadChatFromFile(filePath);
        } else {
            updateWelcomeMessageVisibility();
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(Locale.getDefault());
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS: This Language is not supported");
            }
        } else {
            Log.e(TAG, "TTS: Initialization Failed!");
        }
    }

    private void speakOut(String text) {
        if (tts != null && !TextUtils.isEmpty(text)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
            } else {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        updateWelcomeMessageVisibility();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        aiApiKey = prefs.getString(API_KEY_PREF, null);
        selectedAiModel = prefs.getString(AI_MODEL_PREF, "Gemini 2.5 Flash");
        customModel = prefs.getString(CUSTOM_MODEL_PREF, ""); 
        temperature = prefs.getFloat(TEMPERATURE_PREF, 0.9f);
        topK = prefs.getInt(TOP_K_PREF, 40);
        topP = prefs.getFloat(TOP_P_PREF, 0.9f);
    }

    private void showApiKeyDialog(final boolean isFirstLaunch) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(isFirstLaunch ? R.string.error_empty : R.string.enter_api_key);

        final EditText input = new EditText(this);
        input.setHint(R.string.enter_api_key);
        if (!TextUtils.isEmpty(aiApiKey) && !isFirstLaunch) {
            input.setText(aiApiKey);
        }
        builder.setView(input);

        builder.setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String key = input.getText().toString().trim();
                if (!TextUtils.isEmpty(key)) {
                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putString(API_KEY_PREF, key);
                    editor.apply();
                    aiApiKey = key;
                    Toast.makeText(MainActivity.this, R.string.api_key_saved, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, R.string.error_empty, Toast.LENGTH_LONG).show();
                    if (isFirstLaunch) {
                        showApiKeyDialog(true);
                    }
                }
            }
        });

        if (isFirstLaunch) {
            builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                    Toast.makeText(MainActivity.this, R.string.without_api, Toast.LENGTH_LONG).show();
                }
            });
        } else {
            builder.setNegativeButton(android.R.string.cancel, null);
        }

        builder.show();
    }

    private void sendMessage() {
        String prompt = promptEditText.getText().toString().trim();
        if (TextUtils.isEmpty(prompt)) {
            Toast.makeText(this, R.string.enter_message, Toast.LENGTH_SHORT).show();
            return;
        }

        if (TextUtils.isEmpty(aiApiKey)) {
            Toast.makeText(this, R.string.enter_api_key, Toast.LENGTH_LONG).show();
            showApiKeyDialog(false);
            return;
        }

        chatMessages.add(new ChatMessage(prompt, ChatMessage.SenderRole.USER));
        chatAdapter.notifyDataSetChanged();
        promptEditText.setText("");
        updateWelcomeMessageVisibility();
        new AICallTask(isVoiceInputActive).execute(chatMessages);
        isVoiceInputActive = false; // Reset after sending message
    }

    private void startVoiceInput() {
        isVoiceInputActive = true;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, R.string.say);

        try {
            startActivityForResult(intent, SPEECH_INPUT_REQUEST_CODE);
        } catch (ActivityNotFoundException a) {
            Toast.makeText(getApplicationContext(),
                    R.string.error_voice_input,
                    Toast.LENGTH_SHORT).show();
            isVoiceInputActive = false; // Reset if activity not found
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == SPEECH_INPUT_REQUEST_CODE) {
            if (resultCode == RESULT_OK && null != data) {
                ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (result != null && !result.isEmpty()) {
                    promptEditText.setText(result.get(0));
                    sendMessage();
                } else {
                    isVoiceInputActive = false;
                }
            } else {
                isVoiceInputActive = false;
            }
        }
        else if (requestCode == MANAGE_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    saveChatHistory();
                } else {
                    Toast.makeText(MainActivity.this, R.string.permission_denied, Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            Intent settingsIntent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(settingsIntent);
            return true;
        } else if (id == R.id.action_save_chat) {
            checkStoragePermissionAndSaveChat();
            return true;
        } else if (id == R.id.action_view_saved_chats) {
            Intent historyIntent = new Intent(MainActivity.this, ChatHistoryActivity.class);
            startActivity(historyIntent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void checkStoragePermissionAndSaveChat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                saveChatHistory();
            } else {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                    startActivityForResult(intent, MANAGE_EXTERNAL_STORAGE_REQUEST_CODE);
                } catch (ActivityNotFoundException e) {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, MANAGE_EXTERNAL_STORAGE_REQUEST_CODE);
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_WRITE_EXTERNAL_STORAGE);
            } else {
                saveChatHistory();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveChatHistory();
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void saveChatHistory() {
        if (chatMessages.isEmpty()) {
            Toast.makeText(this, R.string.no_chat_for_save, Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Environment.isExternalStorageLegacy() && !Environment.isExternalStorageManager()) {
            Toast.makeText(this, "Saving chats to external media may be restricted on Android 10+ without the special ‘Access All Files’ permission.", Toast.LENGTH_LONG).show();
        }

        File chatDir = new File(Environment.getExternalStorageDirectory(), "ReOldAI/Chats");
        if (!chatDir.exists()) {
            boolean created = chatDir.mkdirs();
            if (!created) {
                Toast.makeText(this, R.string.error_create_chat, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        long timestamp = System.currentTimeMillis();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String dateString = sdf.format(new Date(timestamp));
        String filename = "chat_history_" + dateString + ".json";
        File chatFile = new File(chatDir, filename);

        Gson gson = new Gson();
        String jsonChatHistory = gson.toJson(chatMessages);

        try (FileOutputStream fos = new FileOutputStream(chatFile)) {
            fos.write(jsonChatHistory.getBytes("UTF-8"));
            String chatName = chatMessages.get(0).text;
            if (chatName.length() > 30) {
                chatName = chatName.substring(0, 30) + "...";
            }
            saveChatInfo(new SavedChatInfo(getString(R.string.chat_history_name_format, dateString) + " - " + chatName, chatFile.getAbsolutePath(), timestamp));
            Log.d(TAG, "Chat saved to: " + chatFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Error saving chat: " + e.getMessage());
            Toast.makeText(this, getString(R.string.failed_to_save_chat, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void saveChatInfo(SavedChatInfo info) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        List<SavedChatInfo> savedChats = loadSavedChats();
        boolean found = false;
        for (int i = 0; i < savedChats.size(); i++) {
            if (savedChats.get(i).getFilePath().equals(info.getFilePath())) {
                savedChats.set(i, info);
                found = true;
                break;
            }
        }
        if (!found) {
            savedChats.add(info);
        }
        String jsonSavedChats = new Gson().toJson(savedChats);
        editor.putString(CHAT_HISTORY_LIST_PREF, jsonSavedChats);
        editor.apply();
    }

    private List<SavedChatInfo> loadSavedChats() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String jsonSavedChats = prefs.getString(CHAT_HISTORY_LIST_PREF, "[]");
        Type type = new TypeToken<List<SavedChatInfo>>(){}.getType();
        return new Gson().fromJson(jsonSavedChats, type);
    }

    private void loadChatFromFile(String filePath) {
        File chatFile = new File(filePath);
        if (!chatFile.exists()) {
            Toast.makeText(this, R.string.no_chat_history + filePath, Toast.LENGTH_LONG).show();
            return;
        }

        Gson gson = new Gson();
        StringBuilder fileContent = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(chatFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                fileContent.append(line);
            }

            Type type = new TypeToken<ArrayList<ChatMessage>>(){}.getType();
            ArrayList<ChatMessage> loadedMessages = gson.fromJson(fileContent.toString(), type);

            if (loadedMessages != null && !loadedMessages.isEmpty()) {
                chatMessages.clear();
                chatMessages.addAll(loadedMessages);
                chatAdapter.notifyDataSetChanged();
                chatListView.smoothScrollToPosition(chatMessages.size() - 1);
            } else {
                Toast.makeText(this, R.string.error_chat_load, Toast.LENGTH_LONG).show();
            }
            updateWelcomeMessageVisibility();
        } catch (Exception e) {
            Log.e(TAG, "Error loading chat from file: " + e.getMessage());
            Toast.makeText(this, R.string.error_chat_load + e.getMessage(), Toast.LENGTH_LONG).show();
            updateWelcomeMessageVisibility();
        }
    }

    private void updateWelcomeMessageVisibility() {
        if (chatMessages.isEmpty()) {
            welcomeMessageTextView.setVisibility(View.VISIBLE);
            chatListView.setVisibility(View.GONE);
        } else {
            welcomeMessageTextView.setVisibility(View.GONE);
            chatListView.setVisibility(View.VISIBLE);
        }
    }

    public void onChatMessageListChanged() {
        updateWelcomeMessageVisibility();
    }


    public static class ChatMessage {
        public enum SenderRole { USER, MODEL }
        String text;
        SenderRole role;

        ChatMessage(String text, SenderRole role) {
            this.text = text;
            this.role = role;
        }
    }
    private SpannableString parseBold(String text) {
        SpannableString spannable = new SpannableString(text);
        android.text.style.StyleSpan boldSpan;
        int start = -1;
        int offset = 0;
        String clean = text;
        while (true) {
            int open = clean.indexOf("**", offset);
            if (open == -1) break;
            int close = clean.indexOf("**", open + 2);
            if (close == -1) break;
            start = open;
            int end = close + 2;
            String before = clean.substring(0, open);
            String middle = clean.substring(open + 2, close);
            String after = clean.substring(end);
            clean = before + middle + after;
            spannable = new SpannableString(clean);
            spannable.setSpan(
                    new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    open, open + middle.length(),
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            offset = open + middle.length();
        }
        return spannable;
    }
    private class ChatMessageAdapter extends ArrayAdapter<ChatMessage> {
        private MainActivity mainActivityContext;

        public ChatMessageAdapter(Context context, ArrayList<ChatMessage> messages, MainActivity mainActivityContext) {
            super(context, 0, messages);
            this.mainActivityContext = mainActivityContext;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            final ChatMessage message = getItem(position);

            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.chat_message_item, parent, false);
            }

            RelativeLayout userMessageLayout = convertView.findViewById(R.id.user_message_layout);
            TextView userMessageTextView = convertView.findViewById(R.id.user_message_text_view);
            ImageButton btnCopyUser = convertView.findViewById(R.id.btn_copy_user);
            ImageButton btnDeleteUser = convertView.findViewById(R.id.btn_delete_user);

            RelativeLayout aiMessageLayout = convertView.findViewById(R.id.ai_message_layout);
            TextView aiMessageTextView = convertView.findViewById(R.id.ai_message_text_view);
            ImageButton btnCopyAi = convertView.findViewById(R.id.btn_copy_ai);
            ImageButton btnDeleteAi = convertView.findViewById(R.id.btn_delete_ai);
            ImageButton btnTtsAi = convertView.findViewById(R.id.btn_tts_ai);


            if (message.role == ChatMessage.SenderRole.USER) {
                userMessageLayout.setVisibility(View.VISIBLE);
                aiMessageLayout.setVisibility(View.GONE);
                userMessageTextView.setText(parseBold(message.text));

                btnCopyUser.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        copyTextToClipboard(message.text);
                    }
                });
                btnDeleteUser.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        deleteMessage(position);
                    }
                });

            } else { // message.role == ChatMessage.SenderRole.MODEL
                userMessageLayout.setVisibility(View.GONE);
                aiMessageLayout.setVisibility(View.VISIBLE);
                aiMessageTextView.setText(parseBold(message.text));

                btnCopyAi.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        copyTextToClipboard(message.text);
                    }
                });
                btnDeleteAi.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        deleteMessage(position);
                    }
                });
                btnTtsAi.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (getContext() instanceof MainActivity) {
                            ((MainActivity) getContext()).speakOut(message.text);
                        }
                    }
                });
            }

            return convertView;
        }

        private void copyTextToClipboard(String text) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("AI Chat Message", text);
                clipboard.setPrimaryClip(clip);
            }
            else {
                android.text.ClipboardManager clipboard =
                        (android.text.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setText(text);
            }
        }

        private void deleteMessage(int position) {
            chatMessages.remove(position);
            notifyDataSetChanged();
            if (mainActivityContext != null) {
                mainActivityContext.onChatMessageListChanged();
            }
        }
    }

    private class AICallTask extends AsyncTask<List<ChatMessage>, Void, String> {
        private String currentAiModel;
        private boolean shouldSpeakResponse;

        public AICallTask(boolean shouldSpeakResponse) {
            this.shouldSpeakResponse = shouldSpeakResponse;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            switch (selectedAiModel) {
                case "Gemini 3.0 Flash":
                    currentAiModel = "gemini-3-flash-preview";
                    break;
                case "Gemini 2.5 Flash":
                    currentAiModel = "gemini-2.5-flash";
                    break;
                default: // Другая модель
                    currentAiModel = !TextUtils.isEmpty(customModel) ? customModel : "gemini-3-flash-preview";
                    break;
            }
        }

        @Override
        protected String doInBackground(List<ChatMessage>... chatHistory) {
            if (chatHistory.length == 0 || chatHistory[0].isEmpty() || TextUtils.isEmpty(aiApiKey)) {
                return "Error: Prompt or API key is not specified.";
            }

            List<ChatMessage> history = chatHistory[0];
            String apiUrl = String.format("https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s", currentAiModel, aiApiKey);
            HttpURLConnection urlConnection = null;
            BufferedReader reader = null;
            String aiResponse = null;
            Gson gson = new Gson();

            try {
                URL url = new URL(apiUrl);
                urlConnection = (HttpURLConnection) url.openConnection();

                urlConnection.setRequestMethod("POST");
                urlConnection.setRequestProperty("Content-Type", "application/json");
                urlConnection.setDoOutput(true);
                urlConnection.setConnectTimeout(10000);
                urlConnection.setReadTimeout(45000);

                JsonObject requestBody = new JsonObject();
                JsonArray contentsArray = new JsonArray();

                for (ChatMessage msg : history) {
                    JsonObject contentObj = new JsonObject();
                    contentObj.addProperty("role", msg.role == ChatMessage.SenderRole.USER ? "user" : "model");
                    JsonArray partsArray = new JsonArray();
                    JsonObject partObj = new JsonObject();
                    partObj.addProperty("text", msg.text);
                    partsArray.add(partObj);
                    contentObj.add("parts", partsArray);
                    contentsArray.add(contentObj);
                }
                requestBody.add("contents", contentsArray);

                JsonObject generationConfig = new JsonObject();
                generationConfig.addProperty("temperature", temperature);
                generationConfig.addProperty("topK", topK);
                generationConfig.addProperty("topP", topP);
                requestBody.add("generationConfig", generationConfig);

                String jsonInputString = gson.toJson(requestBody);
                Log.d(TAG, "Request JSON: " + jsonInputString);

                OutputStream os = urlConnection.getOutputStream();
                os.write(jsonInputString.getBytes("UTF-8"));
                os.flush();
                os.close();

                int responseCode = urlConnection.getResponseCode();
                Log.d(TAG, "HTTP Response Code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), "UTF-8"));
                    StringBuilder responseBuilder = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        responseBuilder.append(line);
                    }
                    aiResponse = responseBuilder.toString();
                    Log.d(TAG, "AI Raw Response: " + aiResponse);

                    try {
                        JsonObject jsonResponse = gson.fromJson(aiResponse, JsonObject.class);
                        if (jsonResponse != null && jsonResponse.has("candidates")) {
                            if (jsonResponse.getAsJsonArray("candidates").size() > 0 &&
                                    jsonResponse.getAsJsonArray("candidates").get(0).getAsJsonObject().getAsJsonObject("content").has("parts")) {
                                String candidateText = jsonResponse.getAsJsonArray("candidates")
                                        .get(0).getAsJsonObject()
                                        .getAsJsonObject("content")
                                        .getAsJsonArray("parts")
                                        .get(0).getAsJsonObject()
                                        .get("text").getAsString();
                                return candidateText;
                            } else {
                                return "Gemini: The model rejected the response due to unsafe content.";
                            }
                        } else if (jsonResponse != null && jsonResponse.has("error")) {
                            JsonObject error = jsonResponse.getAsJsonObject("error");
                            String errorMessage = error.has("message") ? error.get("message").getAsString() : "Unknown API error";
                            int errorCode = error.has("code") ? error.get("code").getAsInt() : -1;
                            return "Error API (" + errorCode + "): " + errorMessage;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "AI response parsing error: " + e.getMessage());
                        return "AI response parsing error: " + e.getMessage();
                    }

                } else {
                    InputStreamReader errorStreamReader = new InputStreamReader(urlConnection.getErrorStream(), "UTF-8");
                    BufferedReader errorReader = new BufferedReader(errorStreamReader);
                    StringBuilder errorResponseBuilder = new StringBuilder();
                    String errorLine;
                    while ((errorLine = errorReader.readLine()) != null) {
                        errorResponseBuilder.append(errorLine);
                    }
                    Log.e(TAG, "Gemini API Error Response: " + errorResponseBuilder.toString());
                    return "Error API: " + responseCode + " - " + errorResponseBuilder.toString();
                }

            } catch (Exception e) {
                Log.e(TAG, "Err with Gemini API: " + e.getMessage());
                e.printStackTrace();
                return "Err with Gemini API: " + e.getMessage();
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Exception e) {
                        Log.e(TAG, "Err close reader: " + e.getMessage());
                    }
                }
            }
            return "Unknown error while receiving a response.";
        }

        @Override
        protected void onPostExecute(String result) {
            super.onPostExecute(result);
            chatMessages.add(new ChatMessage(result, ChatMessage.SenderRole.MODEL));
            chatAdapter.notifyDataSetChanged();
            chatListView.smoothScrollToPosition(chatMessages.size() - 1);
            updateWelcomeMessageVisibility();
            if (shouldSpeakResponse) {
                speakOut(result);
            }
        }
    }
}