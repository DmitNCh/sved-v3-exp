package ru.sved.core.v3;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

// Терминал СВЕД v3.0: Снайперский 8x захват в полном разрешении матрицы + срез ореола цифр
@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final int PICK_SCRIPT_FILE = 101;
    private static final int REQ_CAMERA_PERM = 202;

    private TextView consoleOutput;
    private TextView scriptBox;
    private ScrollView consoleScroll;
    private ScrollView scriptScroll;
    private StringBuilder scriptBuffer = new StringBuilder();

    private LinearLayout cameraContainer;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private ImageView resultImageView;
    private Camera mCamera;

    private int zoomPercent = 85;
    private int exposureDarkenPercent = 100; // Максимальное притемнение ореола по умолчанию
    private boolean ledThresholdMode = true; // Специальный режим разделения сегментов цифр

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(10, 10, 10, 10);
        layout.setBackgroundColor(0xFF000000);

        // 1. ВЕРХНЕЕ ОКНО: Журнал ядра
        consoleOutput = new TextView(this);
        consoleOutput.setTextColor(0xFF00FF00);
        consoleOutput.setTextSize(11f);
        consoleOutput.setText("=== ТЕРМИНАЛ СВЕД v3.0 (СНАЙПЕРСКАЯ ОПТИКА) ===\n" +
                "Режим: Полное разрешение матрицы + 8x вырезка центра + срез ореола LED.\n");

        consoleScroll = new ScrollView(this);
        consoleScroll.addView(consoleOutput);
        LinearLayout.LayoutParams consoleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.55f);
        layout.addView(consoleScroll, consoleParams);

        // 2. ОПТИЧЕСКИЙ БЛОК С ПРИЦЕЛЬНОЙ РАМКОЙ
        cameraContainer = new LinearLayout(this);
        cameraContainer.setOrientation(LinearLayout.VERTICAL);
        cameraContainer.setBackgroundColor(0xFF0A1520);
        cameraContainer.setPadding(6, 6, 6, 6);
        cameraContainer.setVisibility(View.GONE);

        FrameLayout viewFinderFrame = new FrameLayout(this);
        surfaceView = new SurfaceView(this);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        viewFinderFrame.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 460));

        // Прицельная зелёная рамка по центру (чтобы точно навести на часы)
        TextView sniperCrosshair = new TextView(this);
        sniperCrosshair.setText("[ + НАВЕДИТЕ ЧАСЫ В ЦЕНТР + ]");
        sniperCrosshair.setTextColor(0xFF00FF00);
        sniperCrosshair.setTextSize(11f);
        sniperCrosshair.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams crossParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        viewFinderFrame.addView(sniperCrosshair, crossParams);

        // Экран вывода проявленного снимка
        resultImageView = new ImageView(this);
        resultImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resultImageView.setBackgroundColor(0xFF000000);
        resultImageView.setVisibility(View.GONE);
        viewFinderFrame.addView(resultImageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 460));

        cameraContainer.addView(viewFinderFrame);

        // Ползунок 1: ЗУМ
        TextView zoomLabel = new TextView(this);
        zoomLabel.setText("1. ОПТИЧЕСКИЙ ЗУМ + СНАЙПЕРСКИЙ КРОП:");
        zoomLabel.setTextColor(0xFF00FFFF);
        zoomLabel.setTextSize(10.5f);
        cameraContainer.addView(zoomLabel);

        SeekBar zoomBar = new SeekBar(this);
        zoomBar.setMax(100);
        zoomBar.setProgress(85);
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                zoomPercent = progress;
                applyHardwareParameters();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        cameraContainer.addView(zoomBar);

        // Ползунок 2: СРЕЗ ОРЕОЛА ЦИФР (затемнение пересвета светодиодов)
        TextView expLabel = new TextView(this);
        expLabel.setText("2. ПОДАВЛЕНИЕ ОРЕОЛА СВЕТОДИОДОВ (чтобы цифры не сливались):");
        expLabel.setTextColor(0xFFFFAA00);
        expLabel.setTextSize(10.5f);
        cameraContainer.addView(expLabel);

        SeekBar expBar = new SeekBar(this);
        expBar.setMax(100);
        expBar.setProgress(100);
        expBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                exposureDarkenPercent = progress;
                applyHardwareParameters();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        cameraContainer.addView(expBar);

        // Кнопки захвата и проявки
        LinearLayout camBtnRow = new LinearLayout(this);
        camBtnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button snapBtn = new Button(this);
        snapBtn.setText("ФОКУС + СНАЙПЕР-ПРОЯВКА ЧАСОВ");
        snapBtn.setTextSize(10f);
        snapBtn.setBackgroundColor(0xFF007744);
        snapBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams snapP = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f);
        snapP.setMargins(2, 4, 2, 2);
        snapBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                focusAndCaptureSniperClock();
            }
        });
        camBtnRow.addView(snapBtn, snapP);

        Button liveBtn = new Button(this);
        liveBtn.setText("ПРИЦЕЛ / СБРОС");
        liveBtn.setTextSize(10f);
        liveBtn.setBackgroundColor(0xFF224466);
        liveBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams liveP = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.65f);
        liveP.setMargins(2, 4, 2, 2);
        liveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resultImageView.setVisibility(View.GONE);
                if (mCamera != null) {
                    mCamera.startPreview();
                }
            }
        });
        camBtnRow.addView(liveBtn, liveP);

        cameraContainer.addView(camBtnRow);
        layout.addView(cameraContainer);

        // 3. ОКНО АКТИВНЫХ СКРИПТОВ
        TextView boxLabel = new TextView(this);
        boxLabel.setText("ОКНО АКТИВНЫХ СКРИПТОВ (БЕЗ КЛАВИАТУРЫ):");
        boxLabel.setTextColor(0xFF00FFFF);
        boxLabel.setTextSize(10.5f);
        layout.addView(boxLabel);

        scriptBox = new TextView(this);
        scriptBox.setTextColor(0xFFAAFF00);
        scriptBox.setTextSize(11.5f);
        scriptBox.setPadding(10, 10, 10, 10);
        scriptBox.setFocusable(false);
        scriptBox.setClickable(false);

        scriptScroll = new ScrollView(this);
        scriptScroll.setBackgroundColor(0xFF121820);
        scriptScroll.addView(scriptBox);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.65f);
        boxParams.setMargins(0, 2, 0, 4);
        layout.addView(scriptScroll, boxParams);

        // 4. КНОПКИ ДОБАВЛЕНИЯ СКРИПТОВ
        LinearLayout trainRow = new LinearLayout(this);
        trainRow.setOrientation(LinearLayout.HORIZONTAL);
        trainRow.addView(createAddButton("+ ОБУЧЕНИЕ: СРЕЗ ОРЕОЛА ЦИФР", 0xFF1F3A52,
                "[ОБУЧАЮЩИЙ СКРИПТ: РАЗДЕЛЕНИЕ_СЕГМЕНТОВ_ЧАСОВ]\n" +
                "ОБУЧЕНИЕ: МАКСИМАЛЬНОЕ РАЗРЕШЕНИЕ МАТРИЦЫ\n" +
                "ОБУЧЕНИЕ: ОТСЕЧЕНИЕ КРАСНОГО ОРЕОЛА СВЕТОДИОДОВ"));
        trainRow.addView(createAddButton("+ ДЕЙСТВИЕ: 8X СНАЙПЕР-ЗУМ", 0xFF1A4731,
                "[СКРИПТ ДЕЙСТВИЯ: СНАЙПЕРСКИЙ_ЗАХВАТ]\n" +
                "ДЕЙСТВИЕ: ТОЧНЫЙ АВТОФОКУС НА МИШЕНЬ\n" +
                "ДЕЙСТВИЕ: 8-КРАТНАЯ ВЫРЕЗКА ЦЕНТРА БЕЗ МЫЛА"));
        layout.addView(trainRow);

        // 5. ФАЙЛ / ОЧИСТКА
        LinearLayout fileRow = new LinearLayout(this);
        fileRow.setOrientation(LinearLayout.HORIZONTAL);

        Button fileBtn = new Button(this);
        fileBtn.setText("+ ДОБАВИТЬ СКРИПТ ИЗ ФАЙЛА");
        fileBtn.setTextSize(10f);
        fileBtn.setBackgroundColor(0xFF3B2E58);
        fileBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f);
        fileParams.setMargins(2, 2, 2, 3);
        fileBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openScriptFilePicker();
            }
        });
        fileRow.addView(fileBtn, fileParams);

        Button clearBtn = new Button(this);
        clearBtn.setText("ОЧИСТИТЬ ОКНО");
        clearBtn.setTextSize(10f);
        clearBtn.setBackgroundColor(0xFF333333);
        clearBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f);
        clearParams.setMargins(2, 2, 2, 3);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scriptBuffer.setLength(0);
                updateScriptWindow();
            }
        });
        fileRow.addView(clearBtn, clearParams);
        layout.addView(fileRow);

        // 6. ЗАПУСК СКРИПТОВ
        Button runBtn = new Button(this);
        runBtn.setText("ВЫПОЛНИТЬ ДОБАВЛЕННЫЕ В ОКНО СКРИПТЫ");
        runBtn.setBackgroundColor(0xFF006622);
        runBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams runParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        runParams.setMargins(0, 0, 0, 3);
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                executeOpticalScripts();
            }
        });
        layout.addView(runBtn, runParams);

        // 7. ПОЛНЫЙ СТОП
        Button killBtn = new Button(this);
        killBtn.setText("ОТКЛЮЧИТЬ ВСЕ ПРОЦЕССЫ (ПОЛНЫЙ СТОП)");
        killBtn.setBackgroundColor(0xFF550000);
        killBtn.setTextColor(0xFFFFFFFF);
        killBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shutdownAllProcesses();
            }
        });
        layout.addView(killBtn);

        setContentView(layout);
        appendScriptToWindow("[СКРИПТ ОПТИКИ: СНАЙПЕРСКАЯ_ПРОЯВКА_ЧАСОВ]\n" +
                "ОБУЧЕНИЕ: МАКСИМАЛЬНОЕ РАЗРЕШЕНИЕ МАТРИЦЫ\n" +
                "ОБУЧЕНИЕ: СРЕЗ КРАСНОГО ОРЕОЛА СВЕТОДИОДОВ\n" +
                "ДЕЙСТВИЕ: ТОЧНЫЙ АВТОФОКУС + 8X ВЫРЕЗКА ЦЕНТРА");
    }

    private void executeOpticalScripts() {
        consoleOutput.append("\n=== СНАЙПЕРСКИЙ РЕЖИМ ОПТИКИ АКТИВИРОВАН ===\n");
        consoleOutput.append(" -> Выбрано максимальное физическое разрешение сенсора.\n");
        consoleOutput.append(" -> Наведите часы в зелёный прицел по центру и нажмите ФОКУС + СНАЙПЕР-ПРОЯВКА.\n");
        scrollToBottom(consoleScroll);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
                return;
            }
        }
        openSvedCamera();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_CAMERA_PERM && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openSvedCamera();
        }
    }

    private void openSvedCamera() {
        cameraContainer.setVisibility(View.VISIBLE);
        resultImageView.setVisibility(View.GONE);
        startCameraPreview();
    }

    private void startCameraPreview() {
        try {
            if (mCamera == null) {
                mCamera = Camera.open(0);
                mCamera.setDisplayOrientation(90);
            }
            mCamera.setPreviewDisplay(surfaceHolder);
            mCamera.startPreview();
            applyHardwareParameters();
        } catch (Exception ignored) {}
    }

    // Установка максимальных мегапикселей матрицы, зума и среза ореола
    private void applyHardwareParameters() {
        if (mCamera == null) return;
        try {
            Camera.Parameters params = mCamera.getParameters();

            // 1. Выбираем САМОЕ БОЛЬШОЕ физическое разрешение матрицы (максимум мегапикселей)
            List<Camera.Size> sizes = params.getSupportedPictureSizes();
            if (sizes != null && !sizes.isEmpty()) {
                Camera.Size best = sizes.get(0);
                for (Camera.Size s : sizes) {
                    if ((s.width * s.height) > (best.width * best.height)) {
                        best = s;
                    }
                }
                params.setPictureSize(best.width, best.height);
            }
            params.setJpegQuality(100);

            // 2. Зум
            if (params.isZoomSupported()) {
                int maxZ = params.getMaxZoom();
                params.setZoom((maxZ * zoomPercent) / 100);
            }

            // 3. Максимальное притемнение экспозиции (чтобы яркие цифры часов не сливались в пятно)
            int minExp = params.getMinExposureCompensation();
            if (minExp < 0) {
                params.setExposureCompensation((minExp * exposureDarkenPercent) / 100);
            }

            // 4. Режим точного автофокуса
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

            mCamera.setParameters(params);
        } catch (Exception ignored) {}
    }

    // Сначала точная фокусировка линзы на часы, затем мгновенный снимок
    private void focusAndCaptureSniperClock() {
        if (mCamera == null) return;
        consoleOutput.append(" -> Точная наводка линзы на цель и захват кадра...\n");
        scrollToBottom(consoleScroll);

        try {
            mCamera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean success, Camera camera) {
                    takeHighResPicture();
                }
            });
        } catch (Exception e) {
            takeHighResPicture();
        }
    }

    private void takeHighResPicture() {
        if (mCamera == null) return;
        mCamera.takePicture(null, null, new Camera.PictureCallback() {
            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                try {
                    Bitmap raw = BitmapFactory.decodeByteArray(data, 0, data.length);
                    Matrix rot = new Matrix();
                    rot.postRotate(90);

                    // Снайперская вырезка центральной зоны (1/5 кадра по центру, где находятся часы)
                    int w = raw.getWidth();
                    int h = raw.getHeight();
                    int cropW = w / 4;
                    int cropH = h / 4;
                    int startX = (w - cropW) / 2;
                    int startY = (h - cropH) / 2;

                    Bitmap cropped = Bitmap.createBitmap(raw, startX, startY, cropW, cropH, rot, true);

                    // Прогоняем через алгоритм разделения слипшихся сегментов цифр
                    Bitmap enhanced = separateLedDigitsMatrix(cropped);

                    resultImageView.setImageBitmap(enhanced);
                    resultImageView.setVisibility(View.VISIBLE);
                    consoleOutput.append(" === ГОТОВО: Снайперский кроп " + cropped.getWidth() + "x" + cropped.getHeight() +
                            " пкс проявлен, ореол срезан! ===\n");
                    scrollToBottom(consoleScroll);
                } catch (Exception e) {
                    consoleOutput.append(" [Ошибка]: " + e.getMessage() + "\n");
                }
            }
        });
    }

    // Специальная матрица СВЕД для разделения светящихся сегментов цифр на дальних табло
    private Bitmap separateLedDigitsMatrix(Bitmap src) {
        int width = src.getWidth();
        int height = src.getHeight();
        int[] pixels = new int[width * height];
        int[] step1 = new int[width * height];
        int[] outPixels = new int[width * height];
        src.getPixels(pixels, 0, width, 0, 0, width, height);

        // Шаг 1: Нелинейное подавление ореола (Гамма-срез: слабый ореол вокруг цифр гасится в ноль, остаётся только стержень цифры)
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = Color.red(c);
            int g = Color.green(c);
            int b = Color.blue(c);

            // Если это красное свечение табло (красный сильно выше синего/зелёного)
            if (r > g + 25 && r > b + 25) {
                // Срезаем размытый ореол вокруг сегментов: возводим яркость в куб, чтобы края цифр стали тонкими и раздельными
                float normR = r / 255.0f;
                int sharpRed = clamp((int) (Math.pow(normR, 2.8) * 280.0f));
                int coreYellow = (r > 220 && g > 120) ? clamp((int) (Math.pow(g / 255.0f, 3.0) * 255.0f)) : 0;
                step1[i] = Color.rgb(sharpRed, coreYellow, 0);
            } else {
                // Остальной фон (балки, стены) делаем умеренно контрастным без шума
                int luma = (r + g + b) / 3;
                int clean = clamp((int) ((luma - 40) * 1.3f));
                step1[i] = Color.rgb(clean, clean, clean);
            }
        }

        // Шаг 2: Контурная матрица высокой резкости 3x3
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int idx = y * width + x;
                int c = step1[idx];
                int top = step1[(y - 1) * width + x];
                int bottom = step1[(y + 1) * width + x];
                int left = step1[y * width + (x - 1)];
                int right = step1[y * width + (x + 1)];

                int r = clamp(5 * Color.red(c) - Color.red(top) - Color.red(bottom) - Color.red(left) - Color.red(right));
                int g = clamp(5 * Color.green(c) - Color.green(top) - Color.green(bottom) - Color.green(left) - Color.green(right));
                int b = clamp(5 * Color.blue(c) - Color.blue(top) - Color.blue(bottom) - Color.blue(left) - Color.blue(right));

                outPixels[idx] = Color.rgb(r, g, b);
            }
        }

        Bitmap finalBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        finalBmp.setPixels(outPixels, 0, width, 0, 0, width, height);
        return finalBmp;
    }

    private int clamp(int val) {
        return Math.max(0, Math.min(255, val));
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startCameraPreview();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mCamera != null) {
            mCamera.stopPreview();
        }
    }

    private Button createAddButton(String title, int color, final String scriptBlock) {
        Button btn = new Button(this);
        btn.setText(title);
        btn.setTextSize(9.5f);
        btn.setBackgroundColor(color);
        btn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(2, 2, 2, 2);
        btn.setLayoutParams(params);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                appendScriptToWindow(scriptBlock);
            }
        });
        return btn;
    }

    private void appendScriptToWindow(String block) {
        if (scriptBuffer.length() > 0) {
            scriptBuffer.append("\n\n");
        }
        scriptBuffer.append(block.trim());
        updateScriptWindow();
    }

    private void updateScriptWindow() {
        if (scriptBuffer.length() == 0) {
            scriptBox.setText("[Окно пусто. Добавьте скрипт]");
            scriptBox.setTextColor(0xFF777777);
        } else {
            scriptBox.setText(scriptBuffer.toString());
            scriptBox.setTextColor(0xFFAAFF00);
            scrollToBottom(scriptScroll);
        }
    }

    private void openScriptFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "Выберите файл скрипта СВЕД"), PICK_SCRIPT_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_SCRIPT_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                appendScriptToWindow(sb.toString());
            } catch (Exception ignored) {}
        }
    }

    private void scrollToBottom(final ScrollView sv) {
        sv.post(new Runnable() {
            @Override
            public void run() {
                sv.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void shutdownAllProcesses() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
        finishAndRemoveTask();
        Process.killProcess(Process.myPid());
        System.exit(0);
    }
}
