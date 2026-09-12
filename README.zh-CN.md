# ⑨Player

![App Icon](./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png)

有声书播放器 支持Anki 

[English](README.md) | [简体中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md)

## 特性

播放有声书  
导出到 Anki（目前仅支持日语）


### 基础播放器功能
- 计时，倍数...等基础功能
- 封面/字幕 切换
- 书架/列表 切换

### m4b
- 章节
- 点击切换显示章节/总时长&进度

### 扩展功能
- [控制模式](#控制模式)
- [悬浮球](#悬浮球)
- 收藏句子
- [手柄](#手柄)支持

### [Mining Workflow](#mining-workflow)
- [音频](#音频)
- 导入Yomitan辞典
- 点击字幕查词或者在收藏查词
- Anki导出

---

# 添加有声书

点击右下角 `+` 后：

1. 选择有声书文件夹
2. 选择音频和 SRT
3. 如果选择自动移动到有声书文件夹 
   App 会自动：
   - 在有声书文件夹下新建一个 `AudX` 文件夹
   - 把音频和 SRT 移动到该文件夹

# 删除书籍

开启自动移动到有声书文件夹后，删除时若不删除源文件，文件会移到 `9Player Sources`，避免刷新后再次导入。

```
目录示例：

有声书文件夹/
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

SRT 可参考：
[SubPlz](https://github.com/kanjieater/SubPlz)

## Mining Workflow

支持Yomitan词汇，音调，词频辞典  

辞典链接
- [明镜日汉双解辞典](https://forum.freemdict.com/t/topic/38630)
- [小学馆日中辞典第三版](https://github.com/DgnFBJkH5k/Golden-Parcel)

Collection
- [MarvNC](https://drive.google.com/drive/folders/1LXMIOoaWASIntlx1w08njNU005lS5lez)
- [SalwynnJP](https://drive.google.com/drive/folders/1CPPAgKzz_PDEb7JUPHGioONTEQg0aCHW)
- [Shoui](https://drive.google.com/drive/folders/1tTdLppnqMfVC5otPlX_cs4ixlIgjv_lH)
- [Bint](https://drive.proton.me/urls/GH0GV6DMEC#RP55zc2DL8vD)

可参考以下设置：

<p>
  <img src="./docs/images/anki-settings-1.jpg" width="260" alt="Anki设置1" />
  <img src="./docs/images/anki-settings-2.jpg" width="260" alt="Anki设置2" />
</p>

```
{cloze-prefix}<b>{cloze-body}</b>{cloze-suffix}
```
```
{cut-audio} 句子音频
{book-title} 音频文件名字
```

Anki模板:[Lapis](https://github.com/donkuri/lapis)
## 手柄

如果要使用“断开手柄蓝牙”功能：

1. 先安装并配置 Shizuku
2. 进入 设置 -> 手柄蓝牙
3. 点击请求 Shizuku 权限

## 音频
可使用本地tts or 导入 [android.db](https://github.com/KamWithK/AnkiconnectAndroid?tab=readme-ov-file#additional-instructions-local-audio)

## 控制模式

用于手势操控  
处于控制模式时，屏幕不会自然熄屏。

## 悬浮窗

设置 -> 有声书 可开启  
测试悬浮窗可切换：  
悬浮字幕/悬浮球/悬浮字幕+悬浮球

播放有声书时 回到桌面/切换app 即可显示  

悬浮球：  
双击：展开控制栏

## 手环端

https://github.com/tza14/9Player-vela

##  特别感谢

- [hoshidicts](https://github.com/Manhhao/hoshidicts)
- [Hoshi-Reader](https://github.com/Manhhao/Hoshi-Reader)
- [Hoshi-Reader-Android](https://github.com/HuangAntimony/Hoshi-Reader-Android)
- [Ankiconnect Android](https://github.com/KamWithK/AnkiconnectAndroid)本地音频
- [Yomitan](https://github.com/yomidevs/yomitan)
- [Voice](https://github.com/PaulWoitaschek/Voice)
- [AudioConverter](https://github.com/renezuidhof/AudioConverter)
- [taglib](https://github.com/Kyant0/taglib)
- [APlayer](https://github.com/rRemix/APlayer)
- [Legado](https://github.com/gedoor/legado)
- [ッツ Ebook Reader](https://github.com/ttu-ttu/ebook-reader)

## License

本项目采用 `GPLv3.0开源。
完整协议见 [LICENSE](LICENSE)。

