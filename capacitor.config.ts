import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.example.rapidocrcapacitor",
  appName: "离线文字识别",
  webDir: "dist",
  server: {
    androidScheme: "https",
  },
  android: {
    backgroundColor: "#f3f5f7",
    allowMixedContent: false,
  },
};

export default config;
