package com.damum.daycaretemi;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import com.damum.daycaretemi.game.HideAndSeekActivity;
import com.damum.daycaretemi.play.MemoryGameActivity;

/** 놀이 메뉴 (MainActivity의 "놀이" 버튼으로 진입): 기억·이야기 놀이, 숨바꼭질. */
public final class GameMenuActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_game_menu);
        ((Button) findViewById(R.id.menu_memory)).setOnClickListener(v ->
                startActivity(new Intent(this, MemoryGameActivity.class)));
        ((Button) findViewById(R.id.menu_hide)).setOnClickListener(v ->
                startActivity(new Intent(this, HideAndSeekActivity.class)));
    }
}
