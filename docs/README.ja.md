# MinClash

[简体中文](../README.md) · [English](README.en.md) · **日本語** · [한국어](README.ko.md)

Mihomo と KernelSU / Magisk 用モジュールを同梱した、Android 向けの root 対応 TUN クライアントです。

[最新版をダウンロード](https://github.com/givsk983/MinClash/releases/latest) · [変更履歴（中国語）](../CHANGELOG.md)

## 動作要件

root 化済みの ARM64 端末、Android 9 以降、TUN 対応カーネル。

## 使い方

1. `MinClash.apk` をインストールし、KernelSU または Magisk で root 権限を付与します。
2. 初回は設定から同梱モジュールをインストールし、端末を再起動します。
3. HTTPS のサブスクリプションを追加するか、ローカルの YAML をインポートし、概要画面で TUN を開始します。

設定ごとにアプリ別のルーティングとカスタムルールを保存でき、バックアップと復元にも対応しています。

[使い方とビルドの詳細（中国語）](guide.zh-CN.md)
