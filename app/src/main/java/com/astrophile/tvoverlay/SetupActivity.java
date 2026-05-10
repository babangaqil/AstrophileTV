package com.astrophile.tvoverlay;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class SetupActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("App berjalan OK!");
        tv.setTextSize(30);
        tv.setPadding(40, 40, 40, 40);
        setContentView(tv);
    }
}
