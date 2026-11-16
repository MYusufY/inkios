package tech.tachion.inkios.games;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import tech.tachion.inkios.eink.Eink;

public final class GameActivity extends Activity {
    static final String EXTRA_GAME = "game";
    static final int SUDOKU = 0, CHESS = 1, GO = 2, REVERSI = 3;

    private GameView view;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        switch (getIntent().getIntExtra(EXTRA_GAME, SUDOKU)) {
            case CHESS: view = new ChessView(this); break;
            case GO: view = new GoView(this); break;
            case REVERSI: view = new ReversiView(this); break;
            default: view = new SudokuView(this);
        }
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Eink.fullRefreshSoon(view, 80);
    }

    @Override
    protected void onPause() {
        view.save();
        super.onPause();
    }
}
