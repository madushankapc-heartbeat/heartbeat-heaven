<!DOCTYPE html>
<html lang="en">

<head>

<meta charset="UTF-8">

<meta
  name="viewport"
  content="width=device-width, initial-scale=1.0"
>

<meta
  name="robots"
  content="noindex, nofollow"
>

<title>Studio Login | HEARTBEAT HEAVEN</title>

<style>

* {
  box-sizing: border-box;
}

html,
body {
  margin: 0;
  min-height: 100%;
  font-family:
    Inter,
    Arial,
    sans-serif;
  background:
    radial-gradient(
      circle at top,
      #25142b 0%,
      #100b13 42%,
      #070707 100%
    );
  color: #ffffff;
}

body {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.login-wrapper {
  width: 100%;
  max-width: 430px;
}

.logo {
  text-align: center;
  margin-bottom: 25px;
}

.logo-heart {
  font-size: 48px;
  line-height: 1;
  margin-bottom: 12px;
}

.logo-title {
  font-size: 25px;
  font-weight: 800;
  letter-spacing: 2px;
}

.logo-subtitle {
  margin-top: 7px;
  color: #aaa;
  font-size: 13px;
}

.card {
  background:
    rgba(20, 20, 24, 0.94);
  border: 1px solid
    rgba(255,255,255,0.09);
  border-radius: 22px;
  padding: 30px;
  box-shadow:
    0 25px 80px
    rgba(0,0,0,0.5);
  backdrop-filter: blur(18px);
}

.heading {
  margin: 0;
  font-size: 23px;
  font-weight: 750;
}

.description {
  margin: 9px 0 25px;
  color: #999;
  font-size: 14px;
  line-height: 1.5;
}

.field {
  margin-bottom: 17px;
}

label {
  display: block;
  margin-bottom: 8px;
  color: #d6d6d6;
  font-size: 13px;
  font-weight: 600;
}

input {
  width: 100%;
  height: 50px;
  border-radius: 12px;
  border: 1px solid
    rgba(255,255,255,0.12);
  outline: none;
  background: #0c0c0f;
  color: #ffffff;
  padding: 0 15px;
  font-size: 15px;
  transition: 0.2s;
}

input:focus {
  border-color:
    rgba(255,255,255,0.35);
  box-shadow:
    0 0 0 3px
    rgba(255,255,255,0.05);
}

button {
  width: 100%;
  height: 51px;
  margin-top: 5px;
  border: 0;
  border-radius: 12px;
  cursor: pointer;
  color: #ffffff;
  font-size: 15px;
  font-weight: 750;
  background:
    linear-gradient(
      135deg,
      #d946ef,
      #8b5cf6
    );
  transition:
    transform 0.15s,
    opacity 0.15s;
}

button:hover {
  transform: translateY(-1px);
}

button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
}

.error {
  display: none;
  margin-bottom: 17px;
  padding: 12px 14px;
  border-radius: 10px;
  background:
    rgba(239,68,68,0.10);
  border: 1px solid
    rgba(239,68,68,0.25);
  color: #ff9a9a;
  font-size: 13px;
  line-height: 1.4;
}

.footer {
  text-align: center;
  margin-top: 20px;
  color: #666;
  font-size: 12px;
}

</style>

</head>


<body>

<div class="login-wrapper">

  <div class="logo">

    <div class="logo-heart">
      ♥
    </div>

    <div class="logo-title">
      HEARTBEAT HEAVEN
    </div>

    <div class="logo-subtitle">
      Original Music by Madushanka
    </div>

  </div>


  <div class="card">

    <h1 class="heading">
      Studio Login
    </h1>

    <p class="description">
      Sign in to manage songs, covers,
      audio files and your music library.
    </p>


    <div
      id="error"
      class="error">
    </div>


    <form id="loginForm">

      <div class="field">

        <label for="username">
          Username
        </label>

        <input
          id="username"
          name="username"
          type="text"
          autocomplete="username"
          required
          autofocus
        >

      </div>


      <div class="field">

        <label for="password">
          Password
        </label>

        <input
          id="password"
          name="password"
          type="password"
          autocomplete="current-password"
          required
        >

      </div>


      <button
        id="loginButton"
        type="submit">

        Sign In to Studio

      </button>

    </form>

  </div>


  <div class="footer">
    HEARTBEAT HEAVEN • Studio
  </div>

</div>


<script>

const form =
  document.getElementById(
    "loginForm"
  );

const username =
  document.getElementById(
    "username"
  );

const password =
  document.getElementById(
    "password"
  );

const button =
  document.getElementById(
    "loginButton"
  );

const errorBox =
  document.getElementById(
    "error"
  );


function showError(message) {

  errorBox.textContent =
    message;

  errorBox.style.display =
    "block";

}


function hideError() {

  errorBox.textContent =
    "";

  errorBox.style.display =
    "none";

}


form.addEventListener(
  "submit",
  async (event) => {

    event.preventDefault();

    hideError();

    button.disabled = true;

    button.textContent =
      "Signing in...";


    try {

      const response =
        await fetch(
          "/api/studio/login",
          {
            method: "POST",

            headers: {
              "Content-Type":
                "application/json"
            },

            credentials:
              "same-origin",

            body:
              JSON.stringify({
                username:
                  username.value.trim(),

                password:
                  password.value
              })
          }
        );


      const data =
        await response.json();


      if (!response.ok) {

        throw new Error(
          data.error ||
          "Invalid username or password."
        );

      }


      window.location.href =
        "/admin.html";


    } catch (error) {

      showError(
        error.message ||
        "Login failed. Please try again."
      );

      button.disabled =
        false;

      button.textContent =
        "Sign In to Studio";

    }

  }
);

</script>

</body>

</html>
