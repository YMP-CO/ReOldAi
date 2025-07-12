package com.ymp.reold.ai;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ChatHistoryActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "AIPrefs";
    private static final String CHAT_HISTORY_LIST_PREF = "chat_history_list";
    public static final String EXTRA_CHAT_FILE_PATH = "chat_file_path";

    private ListView chatHistoryListView;
    private TextView noHistoryTextView;
    private List<SavedChatInfo> savedChatList;
    private SavedChatAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_history);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        chatHistoryListView = findViewById(R.id.chat_history_list_view);
        noHistoryTextView = findViewById(R.id.no_history_text_view);

        loadSavedChatsAndDisplay();

        chatHistoryListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                SavedChatInfo selectedChat = savedChatList.get(position);
                Intent intent = new Intent(ChatHistoryActivity.this, MainActivity.class);
                intent.putExtra(EXTRA_CHAT_FILE_PATH, selectedChat.getFilePath()); // Передаем путь к файлу
                startActivity(intent);
                finish();
            }
        });

        chatHistoryListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                final SavedChatInfo chatToDelete = savedChatList.get(position);
                showDeleteConfirmationDialog(chatToDelete, position);
                return true;
            }
        });
    }

    private void loadSavedChatsAndDisplay() {
        savedChatList = loadSavedChats();
        Collections.sort(savedChatList, new Comparator<SavedChatInfo>() {
            @Override
            public int compare(SavedChatInfo o1, SavedChatInfo o2) {
                return Long.compare(o2.getTimestamp(), o1.getTimestamp());
            }
        });

        adapter = new SavedChatAdapter(this, (ArrayList<SavedChatInfo>) savedChatList);
        chatHistoryListView.setAdapter(adapter);

        updateNoHistoryVisibility();
    }

    private void updateNoHistoryVisibility() {
        if (savedChatList.isEmpty()) {
            noHistoryTextView.setVisibility(View.VISIBLE);
            chatHistoryListView.setVisibility(View.GONE);
        } else {
            noHistoryTextView.setVisibility(View.GONE);
            chatHistoryListView.setVisibility(View.VISIBLE);
        }
    }

    private List<SavedChatInfo> loadSavedChats() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String jsonSavedChats = prefs.getString(CHAT_HISTORY_LIST_PREF, "[]");
        Type type = new TypeToken<List<SavedChatInfo>>(){}.getType();
        return new Gson().fromJson(jsonSavedChats, type);
    }

    private void saveUpdatedChatList() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        String jsonSavedChats = new Gson().toJson(savedChatList);
        editor.putString(CHAT_HISTORY_LIST_PREF, jsonSavedChats);
        editor.apply();
    }

    private void showDeleteConfirmationDialog(final SavedChatInfo chatToDelete, final int position) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_chat_dialog_title)
                .setMessage(getString(R.string.delete_chat_dialog_message, chatToDelete.getName()))
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        deleteChat(chatToDelete, position);
                    }
                })
                .setNegativeButton(android.R.string.no, null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    private void deleteChat(SavedChatInfo chatToDelete, int position) {
        File chatFile = new File(chatToDelete.getFilePath());
        if (chatFile.exists()) {
            if (chatFile.delete()) {
                savedChatList.remove(position);
                saveUpdatedChatList();
                adapter.notifyDataSetChanged();
                updateNoHistoryVisibility();
            } else {
                Toast.makeText(this, R.string.failed_to_delete_chat, Toast.LENGTH_SHORT).show();
            }
        } else {
            savedChatList.remove(position);
            saveUpdatedChatList();
            adapter.notifyDataSetChanged();
            updateNoHistoryVisibility();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static class SavedChatAdapter extends ArrayAdapter<SavedChatInfo> {

        public SavedChatAdapter(Context context, ArrayList<SavedChatInfo> chats) {
            super(context, 0, chats);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            SavedChatInfo chat = getItem(position);

            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.chat_history_list_item, parent, false);
            }

            TextView chatNameTextView = convertView.findViewById(R.id.chat_name_text_view);
            TextView chatPathTextView = convertView.findViewById(R.id.chat_path_text_view);

            if (chat != null) {
                chatNameTextView.setText(chat.getName());
                chatPathTextView.setText(chat.getFilePath());
            }

            return convertView;
        }
    }
}