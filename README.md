# react-native-audio-browser

> **Alpha** - This library is under active development and not yet published to npm. APIs may change without notice.

A modern React Native audio framework featuring a browser-like navigation tree that can be defined manually or through JSON endpoints, built from the ground up with first class support for Android Auto and Apple Car Play.

<hr />

This is a personal fork for the [`MissingCore/Music`](https://github.com/MissingCore/Music) repository with some additions not upstream due to: being more application specific, no iOS equivalent, or not as "ready".

- Support using any CMAKE version via the `CMAKE_VERSION` environment variable due to Windows being a PITA due to long paths ([`c81dfe5`](https://github.com/MissingCore/react-native-audio-browser/commit/c81dfe550f9f6690f5bbc426f62f4fdb9f56448e)).
- Using our [Media3 1.9.3 fork](https://github.com/MissingCore/media/) due to us patching it for our [`react-native-metadata-retriever`](https://github.com/MissingCore/react-native-metadata-retriever) package ([`6f5668a`](https://github.com/MissingCore/react-native-audio-browser/commit/6f5668ad5ac5b4b1a720d32695eac4598b952f1f)).
- Hard-coding our custom app icon into the Media notification ([`ef315af`](https://github.com/MissingCore/react-native-audio-browser/commit/ef315af701067c2619bd1a5c9acf07816fbff039)).
- A new param in `usePolledProgress` to not fire while the app is backgrounded ([`72781d8`](https://github.com/MissingCore/react-native-audio-browser/commit/72781d87dd18a0c99878f322ad819f06b8ab2c08)).
- New `handleBeforeServiceKilled` handler that fires before we determine the service should be killed ([`4f75672`](https://github.com/MissingCore/react-native-audio-browser/commit/4f756722800f079f9392296661a9b07396bf30c5)).
- Removed the "Queue" button in Android Auto's "Now Playing" interface due to us mainly using it with a single-track queue ([`b48be19`](https://github.com/MissingCore/react-native-audio-browser/commit/b48be198efbf1b31ad556b0d6b742ecde480e9bf)).
- Expose a function to clear the cache for displaying data in Android Auto ([`7bc5fef`](https://github.com/MissingCore/react-native-audio-browser/commit/7bc5fefe4d6928b4ab5306a3f4bfc41d0649c268)).
- Always show the "Skip Next" button even when the queue has a single track ([`d91fb62`](https://github.com/MissingCore/react-native-audio-browser/commit/d91fb627eb5db647b4d86bde62546adf14ffbe09)).
- Get `file://` artwork to display in Android Auto ([`70d553a`](https://github.com/MissingCore/react-native-audio-browser/commit/70d553a6f87c5ea65bfcb8c3554965b718625c91), [`09f9173`](https://github.com/MissingCore/react-native-audio-browser/commit/09f9173290bd305fbd716de4aa1b8ce0a68e118c)).
