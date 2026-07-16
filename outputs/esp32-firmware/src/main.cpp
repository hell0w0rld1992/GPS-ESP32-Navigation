/*
 * BikeGPS ESP32-S3 固件
 * 硬件: ESP32-S3 N16R8 + GC9A01 1.28寸圆形屏 (240x240)
 * 协议: 与 bikegps Android/iOS 端兼容 (BLE NUS)
 *
 * 引脚:
 *   GC9A01: VCC-3.3V GND-GND RST-GPIO8 CS-GPIO10 DC-GPIO9 SDA-GPIO11 SCL-GPIO12
 *   (无 BLK 引脚)
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <TFT_eSPI.h>
#include <esp_task_wdt.h>

// ============================================================
// BLE — Nordic UART Service (兼容 bikegps)
// ============================================================
#define NUS_SERVICE_UUID "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define NUS_RX_UUID      "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"  // 手机→ESP
#define NUS_TX_UUID      "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"  // ESP→手机

BLEServer       *pServer     = nullptr;
BLEService      *pService    = nullptr;
BLECharacteristic *pTxChar   = nullptr;  // ESP→手机 (notify)
BLECharacteristic *pRxChar   = nullptr;  // 手机→ESP (write)
bool            bleConnected = false;

// PKT 分片重组
#define PKT_BUF_SIZE 1024
char pktBuffer[PKT_BUF_SIZE];
int  pktOffset = 0;
int  pktTotal  = 0;
int  pktReceived = 0;
bool pktActive = false;

// ============================================================
// TFT — GC9A01 圆形屏
// ============================================================
TFT_eSPI tft = TFT_eSPI();
#define CENTER_X 120
#define CENTER_Y 120
#define SCREEN_R 119

// ============================================================
// 导航状态 (从 JSON 解析)
// ============================================================
struct NavState {
  double  lat = 0, lon = 0;
  float   speed = 0;
  float   heading = 0;
  int     nav = -1;       // -1=off 0=straight 1=right 2=left 3=uturn 4=arrived
  int     ndist = 0;      // 距离下一步 (米)
  char    nst[32] = "";   // 下条路名
  char    timeStr[8] = ""; // HH:MM
  float   alt = 0;
  float   bat = -1;
  float   wtmp = 0;
  int     wrain = 0;
};
NavState navState;
bool     navUpdated = false;

// ============================================================
// 简易 JSON 解析 (不依赖 External library)
// ============================================================
void parseJson(const char *json) {
  NavState s;
  s.nav = -1;

  // 逐字段查找
  const char *p;

  if ((p = strstr(json, "\"lat\":")))  s.lat = atof(p + 6);
  if ((p = strstr(json, "\"lon\":")))  s.lon = atof(p + 6);
  if ((p = strstr(json, "\"speed\":"))) s.speed = atof(p + 7);
  if ((p = strstr(json, "\"heading\":"))) s.heading = atof(p + 9);
  if ((p = strstr(json, "\"alt\":")))  s.alt = atof(p + 6);
  if ((p = strstr(json, "\"nav\":")))  s.nav = atoi(p + 6);
  if ((p = strstr(json, "\"ndist\":"))) s.ndist = atoi(p + 7);
  if ((p = strstr(json, "\"bat\":")))  s.bat = atof(p + 5);
  if ((p = strstr(json, "\"wtmp\":"))) s.wtmp = atof(p + 6);
  if ((p = strstr(json, "\"wrain\":"))) s.wrain = atoi(p + 7);

  // time: "HH:MM"
  if ((p = strstr(json, "\"time\":"))) {
    const char *q = strchr(p + 7, '"');  // skip past opening quote
    if (q) {
      int len = 0;
      const char *start = q;
      q++; // skip opening "
      while (*q && *q != '"' && len < 7) { s.timeStr[len++] = *q; q++; }
      s.timeStr[len] = '\0';
    }
  }

  // nst: street name
  if ((p = strstr(json, "\"nst\":"))) {
    const char *q = strchr(p + 6, '"');
    if (q) {
      q++;  // skip opening "
      int len = 0;
      while (*q && *q != '"' && len < 31) { s.nst[len++] = *q; q++; }
      s.nst[len] = '\0';
    }
  }

  // 只在有 nav 字段时才更新（防止空包刷新屏幕）
  if (strstr(json, "\"nav\":")) {
    navState = s;
    navUpdated = true;
  }
}

// ============================================================
// BLE Callbacks
// ============================================================
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* s) override {
    bleConnected = true;
    Serial.println("[BLE] 已连接");
  }
  void onDisconnect(BLEServer* s) override {
    bleConnected = false;
    Serial.println("[BLE] 已断开，重新广播");
    pServer->startAdvertising();
  }
};

void handlePktData(const char *data, size_t len) {
  // 检查 PKT:NNN/NNN: 前缀
  if (strncmp(data, "PKT:", 4) == 0) {
    int fragIdx = 0, total = 0;
    if (sscanf(data, "PKT:%d/%d:", &fragIdx, &total) == 2) {
      const char *payload = strchr(data + 4, ':');
      if (!payload) return;
      payload++;  // skip ':'
      size_t payloadLen = len - (payload - data);

      if (fragIdx == 1) {
        pktOffset = 0;
        pktTotal  = total;
        pktReceived = 1;
        pktActive = true;
      }

      if (pktActive && fragIdx == pktReceived + 1) {
        memcpy(pktBuffer + pktOffset, payload, payloadLen);
        pktOffset += payloadLen;
        pktReceived++;

        if (pktReceived >= pktTotal) {
          pktBuffer[pktOffset] = '\0';
          parseJson(pktBuffer);
          pktActive = false;
        }
      } else {
        pktActive = false;  // 乱序，放弃
      }
    }
  } else {
    // 无分片，直接解析
    char buf[512];
    size_t copyLen = (len < 511) ? len : 511;
    memcpy(buf, data, copyLen);
    buf[copyLen] = '\0';
    parseJson(buf);
  }
}

class RxCallback : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    std::string rxValue = c->getValue();
    if (rxValue.length() > 0) {
      handlePktData(rxValue.data(), rxValue.length());
    }
  }
};

// ============================================================
// BLE 初始化
// ============================================================
void initBLE() {
  Serial.println("[BLE] 初始化开始...");
  BLEDevice::init("BikeGPS");

  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  pService = pServer->createService(BLEUUID(NUS_SERVICE_UUID));

  pTxChar = pService->createCharacteristic(
    BLEUUID(NUS_TX_UUID),
    BLECharacteristic::PROPERTY_NOTIFY
  );

  pRxChar = pService->createCharacteristic(
    BLEUUID(NUS_RX_UUID),
    BLECharacteristic::PROPERTY_WRITE_NR
  );
  pRxChar->setCallbacks(new RxCallback());

  pService->start();

  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(BLEUUID(NUS_SERVICE_UUID));
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06);
  pAdvertising->setMaxPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.println("[BLE] 已广播");
  delay(100);  // BLE stabilization delay
  Serial.println("[系统] 启动完成");
  Serial.printf("[Heap] 可用: %d\n", ESP.getFreeHeap());
}

// ============================================================
// 显示绘制
// ============================================================
void drawNavArrow(int cx, int cy, int maneuver, uint16_t color) {
  int s = 30;  // arrow half-size

  tft.setTextColor(color, TFT_BLACK);
  tft.setTextDatum(MC_DATUM);
  tft.setTextSize(1);

  switch (maneuver) {
    case -1: // off — 显示 "—"
      tft.setTextFont(4);
      tft.drawString("--", cx, cy);
      break;

    case 0: // 直行 — 箭头向上
      tft.fillTriangle(cx, cy-s, cx-s, cy+s, cx+s, cy+s, color);
      tft.fillRect(cx-s/3, cy+s/2, 2*s/3, s/2, color);
      break;

    case 1: // 右转 — 箭头向右
      tft.fillTriangle(cx+s, cy, cx-s, cy-s, cx-s, cy+s, color);
      tft.fillRect(cx-s, cy-s/3, s, 2*s/3, color);
      break;

    case 2: // 左转 — 箭头向左
      tft.fillTriangle(cx-s, cy, cx+s, cy-s, cx+s, cy+s, color);
      tft.fillRect(cx-s/2, cy-s/3, s, 2*s/3, color);
      break;

    case 3: { // 掉头 — U 形箭头 (向下 + 圆弧)
      // 向下箭头
      tft.fillTriangle(cx, cy+s, cx-s, cy-s, cx+s, cy-s, color);
      tft.fillRect(cx-s/3, cy-s, 2*s/3, s, color);
      // 圆弧
      tft.drawCircle(cx, cy-s/2, s*2/3, color);
      break;
    }

    case 4: // 到达 — 对勾
      tft.setTextFont(4);
      tft.drawString("OK", cx, cy);
      tft.setTextFont(2);
      tft.drawString("到达", cx, cy + 20);
      break;
  }
}

void drawSpeed(int cx, int cy, float speed) {
  char buf[16];
  if (speed >= 100)   snprintf(buf, sizeof(buf), "%.0f", speed);
  else if (speed >= 10) snprintf(buf, sizeof(buf), "%.0f", speed);
  else                  snprintf(buf, sizeof(buf), "%.1f", speed);

  tft.setTextColor(TFT_SILVER, TFT_BLACK);
  tft.setTextDatum(MC_DATUM);
  tft.setTextFont(6);
  tft.drawString(buf, cx, cy - 35);

  tft.setTextFont(2);
  tft.setTextColor(TFT_DARKGREY, TFT_BLACK);
  tft.drawString("km/h", cx, cy - 18);
}

void drawDistance(int cx, int cy, int meters) {
  char buf[32];
  tft.setTextColor(TFT_WHITE, TFT_BLACK);
  tft.setTextDatum(MC_DATUM);
  tft.setTextFont(4);

  if (meters >= 1000) {
    snprintf(buf, sizeof(buf), "%.1fkm", meters / 1000.0);
  } else {
    snprintf(buf, sizeof(buf), "%dm", meters);
  }
  tft.drawString(buf, cx, cy + 42);
}

void drawStreetName(int cx, int cy, const char *name) {
  tft.setTextColor(TFT_CYAN, TFT_BLACK);
  tft.setTextDatum(MC_DATUM);
  tft.setTextFont(2);
  tft.drawString(name, cx, cy + 62);
}

void drawConnectionStatus() {
  // 右上角小圆点
  int x = 220, y = 10, r = 4;
  if (bleConnected) {
    tft.fillCircle(x, y, r, TFT_GREEN);
  } else {
    tft.fillCircle(x, y, r, TFT_RED);
  }
}

void drawBattery(float bat) {
  if (bat < 0) return;
  int x = 10, y = 10;
  tft.drawRect(x, y, 20, 8, TFT_WHITE);
  tft.fillRect(x + 20, y + 2, 3, 4, TFT_WHITE);
  int fillW = (int)(18 * bat);
  if (fillW > 18) fillW = 18;
  if (fillW < 0) fillW = 0;
  uint16_t batColor = (bat > 0.3) ? TFT_GREEN : TFT_RED;
  tft.fillRect(x + 1, y + 1, fillW, 6, batColor);
}

void drawTime(const char *timeStr) {
  if (strlen(timeStr) == 0) return;
  tft.setTextColor(TFT_DARKGREY, TFT_BLACK);
  tft.setTextDatum(MC_DATUM);
  tft.setTextFont(1);
  tft.drawString(timeStr, CENTER_X, SCREEN_R + 4);
}

void drawWeather(float temp, int rain) {
  if (temp == 0 && rain == 0) return;
  char buf[32];
  snprintf(buf, sizeof(buf), "%.0fC %d%%", temp, rain);
  tft.setTextColor(TFT_OLIVE, TFT_BLACK);
  tft.setTextDatum(MC_DATUM);
  tft.setTextFont(1);
  tft.drawString(buf, CENTER_X, 228);
}

// ============================================================
// 全屏刷新
// ============================================================
void redrawDisplay() {
  // 清屏 (用黑圈限制在圆形区域内)
  tft.fillScreen(TFT_BLACK);
  // 画圆形边框
  tft.drawCircle(CENTER_X, CENTER_Y, SCREEN_R, TFT_DARKGREY);

  // 速度
  drawSpeed(CENTER_X, CENTER_Y, navState.speed);

  // 导航箭头
  uint16_t arrowColor = TFT_WHITE;
  if (navState.nav == 4) arrowColor = TFT_GREEN;  // 到达
  drawNavArrow(CENTER_X, CENTER_Y, navState.nav, arrowColor);

  // 距离
  if (navState.nav >= 0 && navState.nav != 4) {
    drawDistance(CENTER_X, CENTER_Y, navState.ndist);
  }

  // 路名
  if (strlen(navState.nst) > 0) {
    drawStreetName(CENTER_X, CENTER_Y, navState.nst);
  }

  // 连接状态
  drawConnectionStatus();
  drawBattery(navState.bat);
  drawTime(navState.timeStr);
  drawWeather(navState.wtmp, navState.wrain);
}

// ============================================================
// 启动画面
// ============================================================
void showSplash() {
  Serial.println("[TFT] 显示启动画面...");

  // 全屏填充不同颜色测试屏幕是否工作
  tft.fillScreen(TFT_RED);
  delay(300);
  tft.fillScreen(TFT_GREEN);
  delay(300);
  tft.fillScreen(TFT_BLUE);
  delay(300);
  tft.fillScreen(TFT_BLACK);

  // 画圆形边框
  tft.drawCircle(CENTER_X, CENTER_Y, SCREEN_R, TFT_DARKGREY);

  // 画测试方块
  tft.fillRect(50, 50, 20, 20, TFT_WHITE);
  tft.fillRect(CENTER_X-10, CENTER_Y-10, 20, 20, TFT_YELLOW);

  tft.setTextColor(TFT_WHITE, TFT_BLACK);
  tft.setTextDatum(MC_DATUM);
  tft.setTextFont(4);
  tft.drawString("BikeGPS", CENTER_X, CENTER_Y - 20);
  tft.setTextFont(2);
  tft.setTextColor(TFT_CYAN, TFT_BLACK);
  tft.drawString("等待连接...", CENTER_X, CENTER_Y + 20);
  // Try different rotations - fill screen with color to test
  tft.setRotation(0);
  tft.fillScreen(TFT_RED);
  delay(200);
  tft.fillScreen(TFT_GREEN);
  delay(200);
  tft.fillScreen(TFT_BLUE);
  delay(200);
  tft.fillScreen(TFT_WHITE);
  delay(200);
  tft.fillScreen(TFT_BLACK);

  // Now draw the splash screen
  tft.drawCircle(CENTER_X, CENTER_Y, SCREEN_R, TFT_DARKGREY);
  tft.fillRect(50, 50, 20, 20, TFT_WHITE);
  tft.fillRect(CENTER_X-10, CENTER_Y-10, 20, 20, TFT_YELLOW);
  tft.setTextColor(TFT_WHITE, TFT_BLACK);
  tft.setTextDatum(MC_DATUM);
  tft.setTextFont(4);
  tft.drawString("BikeGPS", CENTER_X, CENTER_Y - 20);
  tft.setTextFont(2);
  tft.setTextColor(TFT_CYAN, TFT_BLACK);
  tft.drawString("等待连接...", CENTER_X, CENTER_Y + 20);
  tft.setTextColor(TFT_DARKGREY, TFT_BLACK);
  tft.setTextFont(1);
  tft.drawString("ESP32-S3 + GC9A01", CENTER_X, CENTER_Y + 45);

  Serial.println("[TFT] 启动画面完成");
}

// ============================================================
// 主循环
// ============================================================
void setup() {
  Serial.begin(115200);
  delay(2000);
  Serial.println("\n\n=== BikeGPS ESP32-S3 Firmware ===");
  esp_task_wdt_init(15, true);  // 15s watchdog, panic on timeout
  Serial.println("[TEST] GPIO pin test starting...");

  // Test each display pin by setting HIGH for 1s
  int testPins[] = {8, 9, 10, 11, 12};
  const char* pinNames[] = {"RST(8)", "DC(9)", "CS(10)", "SDA(11)", "SCL(12)"};
  for (int i = 0; i < 5; i++) {
    pinMode(testPins[i], OUTPUT);
    digitalWrite(testPins[i], HIGH);
    Serial.printf("[TEST] %s = HIGH\n", pinNames[i]);
    delay(300);
    digitalWrite(testPins[i], LOW);
  }
  Serial.println("[TEST] Pin test done. If display has backlight, check if it flickered.");


  // 检查 PSRAM
  if (psramFound()) {
    Serial.printf("[PSRAM] 已检测: %d bytes 可用, %d bytes 总计\n",
                  ESP.getFreePsram(), ESP.getPsramSize());
  } else {
    Serial.println("[PSRAM] 未检测到 PSRAM!");
  }
  Serial.printf("[Heap] 可用: %d bytes\n", ESP.getFreeHeap());

  // 初始化屏幕
  Serial.println("[TFT] 初始化屏幕...");
  tft.init();
  tft.setRotation(0);
  tft.fillScreen(TFT_BLACK);
  showSplash();

  // 初始化 BLE
  initBLE();

  Serial.println("[系统] 启动完成");
  Serial.printf("[Heap] 启动后可用: %d bytes\n", ESP.getFreeHeap());
}

void loop() {
  esp_task_wdt_reset();  // feed the watchdog
  static unsigned long lastRefresh = 0;

  if (navUpdated) {
    navUpdated = false;
    redrawDisplay();
    lastRefresh = millis();
  }

  // 连接状态变化时也刷新
  static bool lastBleState = false;
  if (bleConnected != lastBleState) {
    lastBleState = bleConnected;
    redrawDisplay();
  }

  delay(50);  // 20 FPS, feeds watchdog
}
