/*
 * BikeGPS ESP32-S3 Firmware
 * Hardware: ESP32-S3 N16R8 + GC9A01 1.28" Round (240x240)
 * Display: Arduino_GFX (tested & working on this hardware)
 * Protocol: BLE NUS (compatible with BikeGPS Android app)
 *
 * Pins: VCC-3.3V GND-GND RST-8 CS-10 DC-9 SDA-11 SCL-12 (no BLK)
 */

#include <Arduino.h>
#include <Arduino_GFX_Library.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <esp_task_wdt.h>

// ============================================================
// Display — Arduino_GC9A01 (使用 FSPI, 与 fluxdown 一致)
// ============================================================
static constexpr int PIN_SCK  = 12;
static constexpr int PIN_MOSI = 11;
static constexpr int PIN_DC   = 9;
static constexpr int PIN_CS   = 10;
static constexpr int PIN_RST  = 8;

Arduino_DataBus *bus = new Arduino_ESP32SPI(
    PIN_DC, PIN_CS, PIN_SCK, PIN_MOSI, GFX_NOT_DEFINED, FSPI);
Arduino_GFX *gfx = new Arduino_GC9A01(bus, PIN_RST, 0 /* rotation */, true /* IPS */);

#define CENTER_X 120
#define CENTER_Y 115

// ============================================================
// BLE — Nordic UART Service (NUS)
// ============================================================
#define NUS_SERVICE_UUID "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define NUS_RX_UUID      "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define NUS_TX_UUID      "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

BLEServer       *pServer     = nullptr;
BLEService      *pService    = nullptr;
BLECharacteristic *pTxChar   = nullptr;
BLECharacteristic *pRxChar   = nullptr;
bool            bleConnected = false;

// ============================================================
// PKT fragment reassembly
// ============================================================
#define PKT_BUF_SIZE 1024
char pktBuffer[PKT_BUF_SIZE];
int  pktOffset = 0, pktTotal = 0, pktReceived = 0;
bool pktActive = false;

// ============================================================
// Navigation state (parsed from JSON)
// ============================================================
struct NavState {
  double  lat = 0, lon = 0;
  float   speed = 0, heading = 0;
  int     nav = -1, ndist = 0;
  char    nst[32] = "", timeStr[8] = "";
  float   alt = 0, bat = -1, wtmp = 0;
  int     wrain = 0;
};
NavState navState;
bool     navUpdated = false;

// ============================================================
// Simple JSON parser
// ============================================================
void parseJson(const char *json) {
  NavState s; s.nav = -1;
  const char *p;
  if ((p = strstr(json, "\"lat\":")))     s.lat     = atof(p + 6);
  if ((p = strstr(json, "\"lon\":")))     s.lon     = atof(p + 6);
  if ((p = strstr(json, "\"speed\":")))   s.speed   = atof(p + 7);
  if ((p = strstr(json, "\"heading\":"))) s.heading = atof(p + 9);
  if ((p = strstr(json, "\"alt\":")))     s.alt     = atof(p + 6);
  if ((p = strstr(json, "\"nav\":")))     s.nav     = atoi(p + 6);
  if ((p = strstr(json, "\"ndist\":")))   s.ndist   = atoi(p + 7);
  if ((p = strstr(json, "\"bat\":")))     s.bat     = atof(p + 5);
  if ((p = strstr(json, "\"wtmp\":")))    s.wtmp    = atof(p + 6);
  if ((p = strstr(json, "\"wrain\":")))   s.wrain   = atoi(p + 7);

  if ((p = strstr(json, "\"time\":"))) {
    const char *q = strchr(p + 7, '"');
    if (q) { int n = 0; for (q++; *q && *q != '"' && n < 7; ++n) s.timeStr[n] = *q++; s.timeStr[n] = 0; }
  }
  if ((p = strstr(json, "\"nst\":"))) {
    const char *q = strchr(p + 6, '"');
    if (q) { int n = 0; for (q++; *q && *q != '"' && n < 31; ++n) s.nst[n] = *q++; s.nst[n] = 0; }
  }
  if (strstr(json, "\"nav\":")) { navState = s; navUpdated = true; }
}

// ============================================================
// BLE callbacks
// ============================================================
class ServerCB : public BLEServerCallbacks {
  void onConnect(BLEServer* s) override { bleConnected = true; Serial.println("[BLE] connected"); }
  void onDisconnect(BLEServer* s) override { bleConnected = false; pServer->startAdvertising(); Serial.println("[BLE] disconnected"); }
};

void handlePktData(const char *data, size_t len) {
  if (strncmp(data, "PKT:", 4) == 0) {
    int fi = 0, total = 0;
    if (sscanf(data, "PKT:%d/%d:", &fi, &total) == 2) {
      const char *payload = strchr(data + 4, ':'); if (!payload) return; payload++;
      size_t plen = len - (payload - data);
      if (fi == 1) { pktOffset = 0; pktTotal = total; pktReceived = 1; pktActive = true; }
      if (pktActive && fi == pktReceived + 1) {
        memcpy(pktBuffer + pktOffset, payload, plen); pktOffset += plen; pktReceived++;
        if (pktReceived >= pktTotal) { pktBuffer[pktOffset] = 0; parseJson(pktBuffer); pktActive = false; }
      } else pktActive = false;
    }
  } else {
    char buf[512]; size_t n = (len < 511) ? len : 511;
    memcpy(buf, data, n); buf[n] = 0;
    parseJson(buf);
  }
}

class RxCB : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    std::string v = c->getValue();
    if (v.length() > 0) handlePktData(v.data(), v.length());
  }
};

void initBLE() {
  BLEDevice::init("BikeGPS");
  pServer = BLEDevice::createServer(); pServer->setCallbacks(new ServerCB());
  pService = pServer->createService(BLEUUID(NUS_SERVICE_UUID));
  pTxChar = pService->createCharacteristic(BLEUUID(NUS_TX_UUID), BLECharacteristic::PROPERTY_NOTIFY);
  pRxChar = pService->createCharacteristic(BLEUUID(NUS_RX_UUID), BLECharacteristic::PROPERTY_WRITE_NR);
  pRxChar->setCallbacks(new RxCB());
  pService->start();
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(BLEUUID(NUS_SERVICE_UUID));
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("[BLE] broadcasting as BikeGPS");
}

// ============================================================
// Display drawing (Arduino_GFX API)
// ============================================================
void drawArrow(int cx, int cy, int maneuver, uint16_t color) {
  int s = 20;
  switch (maneuver) {
    case -1: gfx->setTextSize(3); gfx->setCursor(cx - 12, cy - 10); gfx->print("--"); break;
    case 0:
      gfx->fillTriangle(cx, cy - s, cx - s, cy + s, cx + s, cy + s, color);
      gfx->fillRect(cx - s/3, cy, 2*s/3, s * 2/3, color);
      break;
    case 1: case 3:
      gfx->fillTriangle(cx + s, cy, cx - s, cy - s, cx - s, cy + s, color);
      break;
    case 2:
      gfx->fillTriangle(cx - s, cy, cx + s, cy - s, cx + s, cy + s, color);
      break;
    case 4:
      gfx->setTextSize(3); gfx->setCursor(cx - 12, cy - 10); gfx->print("OK"); break;
  }
}

void drawSpeed(int cx, int cy, float speed) {
  char buf[16];
  if (speed >= 100) snprintf(buf, sizeof(buf), "%.0f", speed);
  else if (speed >= 10) snprintf(buf, sizeof(buf), "%.0f", speed);
  else snprintf(buf, sizeof(buf), "%.1f", speed);
  gfx->setTextColor(RGB565_WHITE);
  gfx->setTextSize(4);
  gfx->setCursor(cx - 30, cy - 40);
  gfx->print(buf);
}

void drawDistance(int cx, int cy, int meters) {
  char buf[16];
  gfx->setTextColor(RGB565_CYAN);
  gfx->setTextSize(3);
  if (meters >= 1000) snprintf(buf, sizeof(buf), "%.1fkm", meters / 1000.0);
  else snprintf(buf, sizeof(buf), "%dm", meters);
  gfx->setCursor(cx - 24, cy + 30);
  gfx->print(buf);
}

void drawStatusBar(float bat, const char *timeStr) {
  gfx->setTextColor(RGB565_DARKGREY);
  gfx->setTextSize(1);
  gfx->setCursor(8, 8);
  if (bat >= 0) { gfx->printf("%.0f%%", bat * 100); gfx->setCursor(55, 8); }
  gfx->print(timeStr);
  // BLE dot
  gfx->fillCircle(230, 10, 4, bleConnected ? RGB565_GREEN : RGB565_RED);
}

void redrawDisplay() {
  gfx->fillScreen(RGB565_BLACK);

  drawStatusBar(navState.bat, navState.timeStr);
  drawSpeed(CENTER_X, CENTER_Y, navState.speed);

  uint16_t arrowColor = (navState.nav == 4) ? RGB565_GREEN : RGB565_WHITE;
  drawArrow(CENTER_X, CENTER_Y + 5, navState.nav, arrowColor);

  if (navState.nav >= 0 && navState.nav != 4)
    drawDistance(CENTER_X, CENTER_Y, navState.ndist);

  if (strlen(navState.nst) > 0) {
    gfx->setTextColor(RGB565_YELLOW);
    gfx->setTextSize(1);
    gfx->setCursor(30, 215);
    gfx->print(navState.nst);
  }
}

void showSplash() {
  // Color test
  gfx->fillScreen(RGB565_RED);    delay(200);
  gfx->fillScreen(RGB565_GREEN);  delay(200);
  gfx->fillScreen(RGB565_BLUE);   delay(200);
  gfx->fillScreen(RGB565_BLACK);

  gfx->setTextColor(RGB565_WHITE);
  gfx->setTextSize(3);
  gfx->setCursor(50, CENTER_Y - 20);
  gfx->print("BikeGPS");
  gfx->setTextSize(1);
  gfx->setCursor(55, CENTER_Y + 15);
  gfx->print("ESP32-S3 + GC9A01");
  gfx->setTextColor(RGB565_CYAN);
  gfx->setCursor(65, CENTER_Y + 30);
  gfx->print("waiting...");
}

// ============================================================
// Setup & Loop
// ============================================================
void setup() {
  Serial.begin(115200);
  delay(1500);
  Serial.println("\n=== BikeGPS ESP32-S3 ===");
  esp_task_wdt_init(15, true);

  // Init display (same as fluxdown)
  gfx->begin();
  gfx->fillScreen(RGB565_BLACK);
  showSplash();
  Serial.println("[TFT] init OK");

  // BLE
  initBLE();
  Serial.println("[SYS] ready");
}

void loop() {
  esp_task_wdt_reset();
  static bool lastBle = false;

  if (navUpdated) { navUpdated = false; redrawDisplay(); }

  if (bleConnected != lastBle) { lastBle = bleConnected; redrawDisplay(); }

  delay(50);
}
