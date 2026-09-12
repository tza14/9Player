# ⑨Player

![App Icon](./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png)

有聲書播放器 支援Anki

[English](README.md) | [简体中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md)

## 特性

播放有聲書  
匯出到 Anki（目前僅支援日語）

### 基礎播放器功能
- 計時、倍速等基礎功能
- 封面/字幕切換
- 書架/列表切換

### m4b
- 章節
- 點擊切換顯示章節/總時長與進度

### 擴展功能
- [控制模式](#控制模式)
- [懸浮窗](#懸浮窗)
- 收藏句子
- [手柄](#手柄)支援

### [Mining Workflow](#mining-workflow)
- [音訊](#音訊)
- 導入 Yomitan 辭典
- 點擊字幕查詞或在收藏中查詞
- 匯出到 Anki

---

# 添加有聲書

點擊右下角 `+` 後：

1. 選擇有聲書資料夾
2. 選擇音訊和 SRT
3. 如果選擇自動移動到有聲書資料夾
   App 會自動：
   - 在有聲書資料夾下新建一個 `AudX` 資料夾
   - 把音訊和 SRT 移動到該資料夾

# 刪除書籍

開啟自動移動到有聲書資料夾後，刪除時若不刪除源檔案，檔案會移到 `9Player Sources`，避免刷新後再次導入。

```
目錄示例：

有聲書資料夾/
├── Aud1/
│   ├── 1.mp3
│   └── 1.srt
├── Aud2/
│   ├── 2.m4b
│   └── 2.srt
└── 9Player Sources/
    └── Aud3/
        ├── 3.mp3
        └── 3.srt
```

SRT 可參考：
[SubPlz](https://github.com/kanjieater/SubPlz)

## Mining Workflow

支援 Yomitan 詞彙、音調、詞頻辭典

辭典連結
- [明镜日汉双解辞典](https://forum.freemdict.com/t/topic/38630)
- [小学馆日中辞典第三版](https://github.com/DgnFBJkH5k/Golden-Parcel)

Collection
- [MarvNC](https://drive.google.com/drive/folders/1LXMIOoaWASIntlx1w08njNU005lS5lez)
- [SalwynnJP](https://drive.google.com/drive/folders/1CPPAgKzz_PDEb7JUPHGioONTEQg0aCHW)
- [Shoui](https://drive.google.com/drive/folders/1tTdLppnqMfVC5otPlX_cs4ixlIgjv_lH)
- [Bint](https://drive.proton.me/urls/GH0GV6DMEC#RP55zc2DL8vD)

可參考以下設定：

<p>
  <img src="./docs/images/anki-settings-1.jpg" width="260" alt="Anki設定1" />
  <img src="./docs/images/anki-settings-2.jpg" width="260" alt="Anki設定2" />
</p>

```text
{cloze-prefix}<b>{cloze-body}</b>{cloze-suffix}
```
```text
{cut-audio} 句子音訊
{book-title} 音訊文件名字
```

Anki模板：[Lapis](https://github.com/donkuri/lapis)

## 手柄

如果要使用「斷開手柄藍牙」功能：

1. 先安裝並配置 Shizuku
2. 進入 設定 -> 手柄藍牙
3. 點擊請求 Shizuku 權限

## 音訊

可使用本地 TTS 或導入 [android.db](https://github.com/KamWithK/AnkiconnectAndroid?tab=readme-ov-file#additional-instructions-local-audio)

## 控制模式

用於手勢操控。

處於控制模式時，螢幕不會自然熄屏。

## 懸浮窗

設定 -> 有聲書 可開啟。  
測試懸浮窗可切換：  
懸浮字幕 / 懸浮球 / 懸浮字幕 + 懸浮球

播放有聲書時，回到桌面 / 切換 app 即可顯示。  

懸浮球：  
雙擊：展開控制欄

## 手環端

https://github.com/tza14/9Player-vela

## 特別感謝

- [hoshidicts](https://github.com/Manhhao/hoshidicts)
- [Hoshi-Reader](https://github.com/Manhhao/Hoshi-Reader)
- [Hoshi-Reader-Android](https://github.com/HuangAntimony/Hoshi-Reader-Android)
- [Ankiconnect Android](https://github.com/KamWithK/AnkiconnectAndroid) 本地音訊
- [Yomitan](https://github.com/yomidevs/yomitan)
- [Voice](https://github.com/PaulWoitaschek/Voice)
- [AudioConverter](https://github.com/renezuidhof/AudioConverter)
- [taglib](https://github.com/Kyant0/taglib)
- [APlayer](https://github.com/rRemix/APlayer)
- [Legado](https://github.com/gedoor/legado)
- [ッツ Ebook Reader](https://github.com/ttu-ttu/ebook-reader)

## License

本專案採用 [GPLv3.0](LICENSE) 開源。
