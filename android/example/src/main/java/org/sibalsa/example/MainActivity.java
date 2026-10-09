// SPDX-License-Identifier: Unlicense
package org.sibalsa.example;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import org.sibalsa.android.SiBalsa;
import org.sibalsa.core.Cancellation;
import org.sibalsa.core.Feedback;
import org.sibalsa.core.InferenceRequest;
import org.sibalsa.core.ModelSpec;

/** Model-free example host. Replace this UI while keeping the library's constraints. */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private TextView status, answer;
    private EditText prompt;
    private Button send;
    private Cancellation generation;
    private String lastPrompt = "";
    private volatile String lastAnswer = "";
    private ModelSpec lastModel;
    private boolean destroyed;
    private SIbalsaApplication app() { return (SIbalsaApplication) getApplication(); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(18, 27, 37));
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(48), dp(24), dp(32)); scroll.addView(layout); setContentView(scroll);
        TextView brand = label("SIbalsa", 34); layout.addView(brand);
        layout.addView(label("내 기기에서 답하고, 함께 개선합니다.", 16));
        status = label(app().status, 13); status.setPadding(0, dp(20), 0, dp(16)); layout.addView(status);
        layout.addView(button("모델 번들 가져오기", v -> chooseFile(10)));
        layout.addView(button("서버 · 학습 설정", v -> settings()));
        layout.addView(button("개발용 CA 인증서 가져오기", v -> chooseFile(11)));
        prompt = new EditText(this); prompt.setHint("모델을 연결한 뒤 질문을 입력하세요"); prompt.setTextColor(Color.WHITE);
        prompt.setHintTextColor(Color.GRAY); prompt.setMinLines(3); layout.addView(prompt);
        send = button("답변 생성", v -> generate()); layout.addView(send);
        layout.addView(button("생성 중단", v -> { if (generation != null) generation.cancel(); }));
        answer = label("기본 APK에는 LLM이 포함되지 않습니다.\n컴퓨터에서 모델을 통합하거나 .sibalsa 번들을 가져오세요.", 17);
        answer.setTextIsSelectable(true); answer.setPadding(0, dp(20), 0, dp(20)); layout.addView(answer);
        LinearLayout votes = new LinearLayout(this);
        votes.addView(button("좋아요", v -> rating(1)), new LinearLayout.LayoutParams(0, -2, 1));
        votes.addView(button("싫어요", v -> rating(-1)), new LinearLayout.LayoutParams(0, -2, 1)); layout.addView(votes);
        layout.addView(button("수정 답변 · 자세한 피드백", v -> feedback()));
        layout.addView(button("두 답변 비교하기", v -> preference()));
        layout.addView(button("공동학습 예약", v -> {
            SiBalsa sdk = app().sdk;
            if (sdk == null) message("먼저 모델을 연결하세요.");
            else message(sdk.scheduleFederation() ? "예약됨. 유휴 · Wi-Fi · 충전 조건을 기다립니다." : "서버 설정과 공동학습 참여를 확인하세요.");
        }));
        layout.addView(button("로컬 피드백 · 대기 전송 삭제", v -> new AlertDialog.Builder(this)
                .setMessage("이 기기의 피드백과 대기 전송을 삭제합니다. 이미 집계된 모델은 되돌리지 않습니다.")
                .setPositiveButton("삭제", (dialog, which) -> {
                    try {
                        if (app().sdk != null) app().sdk.clearLocalData();
                        app().settings().edit().putBoolean("participation", false).commit();
                        app().status = "로컬 데이터 삭제 완료 · 공동학습 꺼짐";
                        message("로컬 데이터 삭제 완료");
                    }
                    catch (Exception error) { message("삭제 오류: " + error.getMessage()); }
                })
                .setNegativeButton("취소", null).show()));
        layout.addView(label("학습·전송: 유휴 상태 + Wi-Fi + 충전 시에만 실행\n대화 원문 미전송 · 차등프라이버시/보안 집계 미사용\n공동학습 서버는 개별 수치 업데이트를 볼 수 있습니다.", 12));
        handler.post(ticker);
        // Opening the UI restores scheduling after a user force-stop; Application startup for
        // a persisted JobService deliberately does not replace its own executing job.
        app().worker.execute(() -> {
            SiBalsa restored = app().sdk;
            if (restored != null && restored.participationEnabled()) restored.scheduleFederation();
        });
    }
    private TextView label(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(Color.rgb(225, 235, 243)); return view;
    }
    private Button button(String value, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(value); button.setAllCaps(false); button.setOnClickListener(listener); return button;
    }
    private final Runnable ticker = new Runnable() {
        @Override public void run() { if (!destroyed) { status.setText(app().status); handler.postDelayed(this, 1000); } }
    };
    private void message(String text) { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show(); }
    private void chooseFile(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*"); startActivityForResult(intent, request);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData(); app().status = "통합 파일 확인 중…";
        app().worker.execute(() -> {
            try {
                if (request == 10) app().configure(ModelBundle.importZip(this, uri));
                else if (request == 11) {
                    File stage = new File(getFilesDir(), "development-ca.tmp");
                    try (InputStream input = getContentResolver().openInputStream(uri); FileOutputStream output = new FileOutputStream(stage)) {
                        byte[] block = new byte[4096]; int count, total = 0;
                        while ((count = input.read(block)) != -1) { total += count; if (total > 65536) throw new IllegalArgumentException("CA certificate too large"); output.write(block, 0, count); }
                        output.getFD().sync();
                    }
                    if (!stage.renameTo(new File(getFilesDir(), "development-ca.crt"))) throw new IllegalStateException("Cannot save certificate");
                    if (app().bundle != null) app().configure(app().bundle.directory); else app().status = "CA 저장됨. 모델을 연결하세요.";
                }
            } catch (Exception | LinkageError error) { app().status = "설정 오류: " + error.getMessage(); }
        });
    }
    private void generate() {
        SiBalsa sdk = app().sdk;
        if (sdk == null) { message("모델을 먼저 연결하세요."); return; }
        String question = prompt.getText().toString().trim();
        if (question.isEmpty()) return;
        generation = new Cancellation(); Cancellation cancellation = generation; send.setEnabled(false); answer.setText("");
        lastPrompt = question; lastAnswer = ""; lastModel = sdk.model();
        app().worker.execute(() -> {
            StringBuilder output = new StringBuilder();
            try {
                sdk.generate(new InferenceRequest(question, 256, .7), cancellation, token -> {
                    output.append(token); String displayed = output.toString(); handler.post(() -> answer.setText(displayed));
                });
                lastAnswer = output.toString();
            } catch (Exception error) { handler.post(() -> answer.setText("생성 중단 또는 오류: " + error.getMessage())); }
            finally { handler.post(() -> send.setEnabled(true)); }
        });
    }
    private boolean readyFeedback() {
        if (app().sdk == null || lastAnswer.isEmpty() || !app().sdk.model().equals(lastModel)) {
            message("현재 모델에서 평가할 답변을 먼저 생성하세요."); return false;
        }
        return true;
    }
    private void save(Feedback.Kind kind, int rating, String chosen, String rejected, String note, Feedback.Reason reason) {
        try {
            app().sdk.recordFeedback(new Feedback(0, app().sdk.model(), lastPrompt, lastAnswer, kind, rating, chosen, rejected, note,
                    reason == null ? Collections.emptyList() : Collections.singletonList(reason)));
            message(kind == Feedback.Kind.RATING ? "평가를 로컬에 저장했습니다. 기본 학습은 수정·비교 답변을 사용합니다." : "학습 가능한 피드백을 로컬에 저장했습니다.");
        } catch (Exception error) { message(error.getMessage()); }
    }
    private void rating(int rating) { if (readyFeedback()) save(Feedback.Kind.RATING, rating, "", "", "", null); }
    private void feedback() {
        if (!readyFeedback()) return;
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), 0, dp(20), 0);
        EditText corrected = new EditText(this); corrected.setHint("수정된 답변 (입력하면 SFT 학습)"); corrected.setMinLines(3); form.addView(corrected);
        EditText note = new EditText(this); note.setHint("추가 설명 (로컬 기록)"); form.addView(note);
        android.widget.Spinner reasons = new android.widget.Spinner(this);
        String[] labels = {"사실 오류", "너무 김", "너무 짧음", "관련 없음", "말투", "요청 불이행", "기타"};
        reasons.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels)); form.addView(reasons);
        new AlertDialog.Builder(this).setTitle("자세한 피드백").setView(form).setNegativeButton("취소", null)
                .setPositiveButton("로컬에 저장", (d, which) -> {
                    String text = corrected.getText().toString();
                    save(text.trim().isEmpty() ? Feedback.Kind.RATING : Feedback.Kind.CORRECTION, -1, text, "", note.getText().toString(), Feedback.Reason.values()[reasons.getSelectedItemPosition()]);
                }).show();
    }
    private void preference() {
        if (!readyFeedback()) return;
        EditText alternative = new EditText(this); alternative.setHint("같은 질문에 대한 더 나은 답변"); alternative.setMinLines(4);
        new AlertDialog.Builder(this).setTitle("선호 답변 입력 · DPO").setView(alternative).setNegativeButton("취소", null)
                .setPositiveButton("입력 답변을 선호", (d, which) -> save(Feedback.Kind.PREFERENCE, 1, alternative.getText().toString(), lastAnswer, "", null)).show();
    }
    private void settings() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), 0, dp(20), 0);
        EditText server = new EditText(this); server.setHint("https://your-coordinator:8443"); server.setText(app().settings().getString("server", "")); form.addView(server);
        EditText pin = new EditText(this); pin.setHint("SPKI pin sha256/base64 (선택)"); pin.setText(app().settings().getString("pin", "")); form.addView(pin);
        EditText minimum = new EditText(this); minimum.setHint("최소 학습 피드백 수"); minimum.setText(Integer.toString(app().settings().getInt("minFeedback", 8))); minimum.setInputType(2); form.addView(minimum);
        EditText steps = new EditText(this); steps.setHint("학습 스텝 제한"); steps.setText(Integer.toString(app().settings().getInt("maxSteps", 8))); steps.setInputType(2); form.addView(steps);
        EditText rate = new EditText(this); rate.setHint("학습률"); rate.setText(app().settings().getString("learningRate", "0.001")); form.addView(rate);
        CheckBox participate = new CheckBox(this); participate.setText("공동학습 참여 (대화 원문 미전송)"); participate.setChecked(app().settings().getBoolean("participation", false)); form.addView(participate);
        CheckBox unmetered = new CheckBox(this); unmetered.setText("요금이 부과되지 않는 Wi-Fi만 사용"); unmetered.setChecked(app().settings().getBoolean("unmetered", true)); form.addView(unmetered);
        form.addView(label("모델이나 서버를 바꾸면 공동학습 참여가 꺼집니다. 새 연결을 확인한 뒤 다시 참여하세요.", 12));
        new AlertDialog.Builder(this).setTitle("통합 설정").setView(form).setNegativeButton("취소", null).setPositiveButton("적용", (dialog, which) -> {
            try {
                int min = Integer.parseInt(minimum.getText().toString()), count = Integer.parseInt(steps.getText().toString());
                org.sibalsa.core.SiBalsaConfig.Builder validator = org.sibalsa.core.SiBalsaConfig.builder().minFeedback(min).maxSamples(Math.max(32, min)).maxSteps(count)
                        .learningRate(Double.parseDouble(rate.getText().toString())).federationEnabled(!server.getText().toString().trim().isEmpty()).requireUnmeteredWifi(unmetered.isChecked());
                if (!server.getText().toString().trim().isEmpty()) validator.server(server.getText().toString().trim());
                if (!pin.getText().toString().trim().isEmpty()) validator.spkiPin(pin.getText().toString().trim());
                validator.build();
                app().settings().edit().putString("server", server.getText().toString().trim()).putString("pin", pin.getText().toString().trim())
                        .putInt("minFeedback", min).putInt("maxSamples", Math.max(32, min)).putInt("maxSteps", count).putString("learningRate", rate.getText().toString())
                        .putBoolean("participation", participate.isChecked()).putBoolean("unmetered", unmetered.isChecked()).commit();
                if (app().bundle != null) app().worker.execute(() -> { try { app().configure(app().bundle.directory); } catch (Exception error) { app().status = "설정 오류: " + error.getMessage(); } });
            } catch (Exception error) { message("설정값 확인: " + error.getMessage()); }
        }).show();
    }
    @Override protected void onDestroy() { destroyed = true; handler.removeCallbacks(ticker); if (generation != null) generation.cancel(); super.onDestroy(); }
}
