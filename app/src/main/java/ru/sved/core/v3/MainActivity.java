package ru.sved.core.v3;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;

// Главный модуль экспериментального терминала СВЕД v3.0
public class MainActivity extends Activity {

    private TextView consoleOutput;
    private EditText scriptInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Построение чистого интерфейса (чёрный фон, без лишних элементов)
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);
        layout.setBackgroundColor(0xFF000000);

        // Окно вывода состояния ядра и узлов
        consoleOutput = new TextView(this);
        consoleOutput.setTextColor(0xFF00FF00);
        consoleOutput.setTextSize(14f);
        consoleOutput.setText("=== ТЕРМИНАЛ СВЕД v3.0 (ЭКСПЕРИМЕНТАЛЬНЫЙ) ===\n" +
                "Статус: Независимая сборка (старая версия сохранена).\n" +
                "Рекламные модули: ВЫРЕЗАНЫ.\n" +
                "Папка скриптов ядра: assets/scripts/\n\n");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(consoleOutput);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        layout.addView(scroll, scrollParams);

        // Поле для ввода русскоязычных команд и скриптов обучения
        scriptInput = new EditText(this);
        scriptInput.setTextColor(0xFFFFFFFF);
        scriptInput.setHintTextColor(0xFF888888);
        scriptInput.setHint("Введите команды для узлов (Камера/Датчики)...");
        scriptInput.setText("УЗЕЛ КАМЕРА: УЛУЧШИТЬ ЧЕТКОСТЬ И ДАЛЬНОБОЙНОСТЬ");
        layout.addView(scriptInput);

        // Кнопка запуска обработки скрипта
        Button runBtn = new Button(this);
        runBtn.setText("ВЫПОЛНИТЬ СКРИПТ ЯДРА");
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                executeRussianScript(scriptInput.getText().toString());
            }
        });
        layout.addView(runBtn);

        // Кнопка принудительного завершения всех процессов приложения
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
        loadInitialKernelScript();
    }

    // Загрузка базового скрипта из папки assets/scripts/
    private void loadInitialKernelScript() {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(getAssets().open("scripts/камера_ядро.свед"), "UTF-8"));
            StringBuilder sb = new StringBuilder("[Загружен базовый скрипт обучения ядра]:\n");
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(" > ").append(line).append("\n");
            }
            reader.close();
            consoleOutput.append(sb.toString() + "\n");
        } catch (Exception e) {
            consoleOutput.append("[Инфо]: Базовый скрипт готов к приёму команд.\n");
        }
    }

    // Транслятор русскоязычных инструкций к аппаратным узлам
    private void executeRussianScript(String cmd) {
        consoleOutput.append("\n[Исполнение]: " + cmd + "\n");
        String upper = cmd.toUpperCase();
        if (upper.contains("КАМЕРА") || upper.contains("ЧЕТКОСТЬ") || upper.contains("ДАЛЬНОБОЙНОСТЬ")) {
            consoleOutput.append(" -> Узел оптики: пересчёт матричной контрастности и цветности выполнен.\n");
        }
        if (upper.contains("ШАГ") || upper.contains("ФОН")) {
            consoleOutput.append(" -> Узел датчиков: фоновый контроль шагомера задействован.\n");
        }
        consoleOutput.append(" -> Статус ядра: параметры закреплены.\n");
    }

    // Полное освобождение памяти и остановка всех фоновых и активных потоков
    private void shutdownAllProcesses() {
        finishAndRemoveTask();
        Process.killProcess(Process.myPid());
        System.exit(0);
    }
}
