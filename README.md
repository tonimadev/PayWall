# PayWall SDK 💳
[![](https://jitpack.io/v/tonimadev/PayWall.svg)](https://jitpack.io/#tonimadev/PayWall)


PayWall is a lightweight, modular, and highly configurable Android SDK for managing **In-App Purchases** and **Subscriptions** using the Google Play Billing Library.

It was designed to be reused across multiple projects, abstracting the complexity of the Billing SDK and providing a simple, `StateFlow`-based reactive API.

## 🚀 Features

- ✅ **Multi-Product Support**: Handle multiple one-time purchases and subscriptions simultaneously.
- ⚡ **Reactive API**: Observe purchase status changes in real-time using Kotlin `StateFlow`.
- 🧩 **Modular Architecture**: 
    - `paywall-core`: Pure Kotlin abstractions (no Android dependencies).
    - `paywall-play`: Google Play Billing implementation.
- 🛠️ **Configurable**: Define your product IDs dynamically at runtime.
- 🔄 **Auto-Acknowledgment**: Automatically handles purchase acknowledgment to prevent refunds.

## 📦 Installation

Add the JitPack repository to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.tonimadev.PayWall:paywall-core:0.1")
    implementation("com.github.tonimadev.PayWall:paywall-play:0.1")
}
```

## 🛠️ Usage

### 1. Configuration

Define which products your app will monitor:

```kotlin
val payWallConfig = PayWallConfig(
    inAppProductIds = setOf("remove_ads_id", "premium_unlock_id"),
    subscriptionProductIds = setOf("monthly_plan_id", "yearly_plan_id"),
    autoAcknowledge = true // Defaults to true
)
```

### 2. Initialization

Initialize the manager (ideally using Hilt or another DI framework):

```kotlin
val payWallManager: PayWallManager = PayWallManagerImpl(context, payWallConfig)

// Connect to Google Play Services
payWallManager.connect()
```

### 3. Observing Purchase Status

The SDK provides a `StateFlow` containing all currently owned product IDs:

```kotlin
lifecycleScope.launch {
    payWallManager.ownedProductIds.collect { ownedIds ->
        if (ownedIds.contains("remove_ads_id")) {
            // Hide ads in your UI
        }
    }
}

// Or use the helper function
if (payWallManager.isPurchased("remove_ads_id")) {
    // Logic here
}
```

### 4. Launching Purchase Flows

```kotlin
// For one-time purchases
payWallManager.launchPurchase(activity, "remove_ads_id")

// For subscriptions
payWallManager.launchSubscription(activity, "monthly_plan_id")

// For subscriptions with specific base plans
payWallManager.launchSubscription(activity, "monthly_plan_id", "base-plan-id")
```

## 🏗️ Architecture

- **`paywall-core`**: Contains `PayWallManager` interface and `PayWallConfig`. Use this in your domain/data layers to keep them decoupled from Google Play.
- **`paywall-play`**: The actual implementation using `com.android.billingclient:billing-ktx`.

## 📄 License

```text
Copyright 2026 digital.tonima

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
