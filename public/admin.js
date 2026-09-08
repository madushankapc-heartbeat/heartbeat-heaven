const uploadForm = document.getElementById("uploadForm");
const editForm = document.getElementById("editForm");

const status = document.getElementById("status");
const editStatus = document.getElementById("editStatus");

const list = document.getElementById("adminSongs");

const editPanel = document.getElementById("editPanel");
const cancelEdit = document.getElementById("cancelEdit");

let songs = [];


/* =========================================================
   PROGRESS UI
========================================================= */

function createProgressUI(container, type = "upload") {

  let box = container.querySelector(".hh-progress");

  if (!box) {

    box = document.createElement("div");

    box.className = "hh-progress";

    box.innerHTML = `
      <div class="hh-progress-top">
        <span class="hh-progress-stage">
          Preparing…
        </span>

        <strong class="hh-progress-percent">
          0%
        </strong>
      </div>

      <div class="hh-progress-track">
        <div class="hh-progress-bar"></div>
      </div>

      <div class="hh-progress-message">
        Please wait…
      </div>
    `;

    container.appendChild(box);
  }

  return box;
}


function updateProgress(
  container,
  percent,
  stage,
  message
) {

  const box =
    container.querySelector(".hh-progress");

  if (!box) return;

  const bar =
    box.querySelector(".hh-progress-bar");

  const percentText =
    box.querySelector(".hh-progress-percent");

  const stageText =
    box.querySelector(".hh-progress-stage");

  const messageText =
    box.querySelector(".hh-progress-message");


  const safePercent =
    Math.max(
      0,
      Math.min(
        100,
        Math.round(percent)
      )
    );


  bar.style.width =
    safePercent + "%";


  percentText.textContent =
    safePercent + "%";


  stageText.textContent =
    stage || "";


  messageText.textContent =
    message || "";
}


function finishProgress(
  container,
  success = true,
  message = ""
) {

  const box =
    container.querySelector(".hh-progress");

  if (!box) return;

  const bar =
    box.querySelector(".hh-progress-bar");

  const percentText =
    box.querySelector(".hh-progress-percent");

  const stageText =
    box.querySelector(".hh-progress-stage");

  const messageText =
    box.querySelector(".hh-progress-message");


  if (success) {

    bar.style.width = "100%";

    percentText.textContent = "100%";

    stageText.textContent =
      "Published successfully";

    messageText.textContent =
      message || "Your song is now live.";

    box.classList.add("success");

  } else {

    stageText.textContent =
      "Upload failed";

    messageText.textContent =
      message || "Please try again.";

    box.classList.add("error");
  }
}


/* =========================================================
   ADD PROGRESS CSS
========================================================= */

(function addProgressStyles() {

  if (document.getElementById("hh-progress-styles")) {
    return;
  }

  const style =
    document.createElement("style");

  style.id =
    "hh-progress-styles";

  style.textContent = `

    .hh-progress {
      margin-top: 16px;
      padding: 15px 16px;
      border: 1px solid rgba(255,255,255,.10);
      border-radius: 14px;
      background: rgba(255,255,255,.035);
      transition: opacity .25s ease;
    }

    .hh-progress-top {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 10px;
    }

    .hh-progress-stage {
      font-size: 13px;
      font-weight: 600;
    }

    .hh-progress-percent {
      font-size: 14px;
      white-space: nowrap;
    }

    .hh-progress-track {
      width: 100%;
      height: 8px;
      overflow: hidden;
      border-radius: 999px;
      background: rgba(255,255,255,.10);
    }

    .hh-progress-bar {
      width: 0%;
      height: 100%;
      border-radius: inherit;
      background: currentColor;
      transition: width .18s ease;
    }

    .hh-progress-message {
      margin-top: 9px;
      font-size: 12px;
      opacity: .68;
      line-height: 1.45;
    }

    .hh-progress.success {
      border-color: rgba(80,220,130,.28);
    }

    .hh-progress.error {
      border-color: rgba(255,80,80,.28);
    }

    .hh-progress.error .hh-progress-bar {
      width: 100%;
    }

    .hh-uploading {
      opacity: .72;
      pointer-events: none;
    }

    @media (max-width: 600px) {

      .hh-progress {
        padding: 13px;
      }

      .hh-progress-stage {
        font-size: 12px;
      }

      .hh-progress-percent {
        font-size: 13px;
      }

    }

  `;

  document.head.appendChild(style);

})();


/* =========================================================
   REAL XHR UPLOAD
========================================================= */

function uploadWithProgress(
  url,
  method,
  formData,
  onProgress
) {

  return new Promise((resolve, reject) => {

    const xhr =
      new XMLHttpRequest();


    xhr.open(
      method,
      url,
      true
    );


    xhr.setRequestHeader(
      "Accept",
      "application/json"
    );


    xhr.upload.addEventListener(
      "progress",
      (event) => {

        if (!event.lengthComputable) {
          return;
        }

        const percent =
          (event.loaded / event.total) * 100;


        onProgress(
          percent,
          event.loaded,
          event.total
        );
      }
    );


    xhr.addEventListener(
      "load",
      () => {

        let data = {};

        try {

          data =
            xhr.responseText
              ? JSON.parse(xhr.responseText)
              : {};

        } catch (error) {

          data = {
            error:
              "Invalid server response."
          };
        }


        resolve({
          ok:
            xhr.status >= 200 &&
            xhr.status < 300,

          status:
            xhr.status,

          data
        });

      }
    );


    xhr.addEventListener(
      "error",
      () => {

        reject(
          new Error(
            "Network error."
          )
        );

      }
    );


    xhr.addEventListener(
      "abort",
      () => {

        reject(
          new Error(
            "Upload cancelled."
          )
        );

      }
    );


    xhr.addEventListener(
      "timeout",
      () => {

        reject(
          new Error(
            "Upload timed out."
          )
        );

      }
    );


    xhr.send(formData);

  });
}


/* =========================================================
   LOAD SONGS
========================================================= */

async function load() {

  try {

    const response =
      await fetch(
        "/api/songs"
      );


    if (!response.ok) {
      throw new Error(
        "Unable to load songs."
      );
    }


    songs =
      await response.json();


    renderSongs();

  } catch (error) {

    console.error(error);

    list.innerHTML =
      '<div class="empty">Unable to load songs.</div>';
  }
}


/* =========================================================
   RENDER SONG LIST
========================================================= */

function renderSongs() {

  if (!songs.length) {

    list.innerHTML =
      '<div class="empty">No songs yet.</div>';

    return;
  }


  list.innerHTML =
    songs.map(song => `

      <div class="admin-row">

        <div class="admin-song-info">

          <img
            class="admin-thumb"
            src="${song.cover_url || ""}"
            alt=""
          >

          <div>

            <b>${esc(song.title)}</b>

            <small class="meta">
              ${esc(song.artist || "Madushanka")}
              •
              ${esc(song.genre || "")}
              •
              ${esc(song.language || "")}
            </small>

          </div>

        </div>


        <div class="admin-actions">

          <button
            class="btn ghost edit-btn"
            onclick="editSong(${song.id})"
          >
            Edit
          </button>


          <button
            class="delete"
            onclick="deleteSong(${song.id})"
          >
            Delete
          </button>

        </div>

      </div>

    `).join("");
}


/* =========================================================
   UPLOAD NEW SONG
========================================================= */

uploadForm.onsubmit = async (e) => {

  e.preventDefault();


  status.className = "";

  status.textContent = "";


  const submitButton =
    uploadForm.querySelector(
      'button[type="submit"]'
    );


  const progress =
    createProgressUI(
      uploadForm,
      "upload"
    );


  uploadForm.classList.add(
    "hh-uploading"
  );


  submitButton.disabled = true;

  submitButton.dataset.originalText =
    submitButton.textContent;

  submitButton.textContent =
    "Publishing…";


  updateProgress(
    uploadForm,
    0,
    "Preparing upload",
    "Preparing your song files…"
  );


  try {

    const formData =
      new FormData(uploadForm);


    updateProgress(
      uploadForm,
      0,
      "Uploading files",
      "Starting secure upload…"
    );


    const result =
      await uploadWithProgress(
        "/api/songs",
        "POST",
        formData,
        (percent) => {

          /*
            Keep the final 5% for server-side
            processing and publishing.
          */

          const displayPercent =
            Math.min(
              95,
              percent * 0.95
            );


          updateProgress(
            uploadForm,
            displayPercent,
            "Uploading files",
            `Uploading audio and cover… ${Math.round(percent)}%`
          );

        }
      );


    if (!result.ok) {

      throw new Error(
        result.data?.error ||
        "Upload failed."
      );
    }


    updateProgress(
      uploadForm,
      97,
      "Publishing",
      "Saving song details and finishing publication…"
    );


    /*
      Give the browser a moment to display
      the publishing stage before success.
    */

    await new Promise(
      resolve =>
        setTimeout(
          resolve,
          250
        )
    );


    finishProgress(
      uploadForm,
      true,
      "Your song has been published successfully."
    );


    status.className =
      "success";

    status.textContent =
      "Published successfully.";


    uploadForm.reset();


    if (uploadForm.artist) {

      uploadForm.artist.value =
        "Madushanka";
    }


    await load();


  } catch (error) {

    console.error(error);


    finishProgress(
      uploadForm,
      false,
      error.message ||
      "Something went wrong."
    );


    status.className =
      "error";

    status.textContent =
      error.message ||
      "Something went wrong. Please try again.";

  } finally {

    uploadForm.classList.remove(
      "hh-uploading"
    );

    submitButton.disabled = false;

    submitButton.textContent =
      submitButton.dataset.originalText ||
      "Publish Song";
  }

};


/* =========================================================
   OPEN EDIT FORM
========================================================= */

function editSong(id) {

  const song =
    songs.find(
      s =>
        Number(s.id) ===
        Number(id)
    );


  if (!song) return;


  document.getElementById(
    "editId"
  ).value =
    song.id;


  document.getElementById(
    "editTitle"
  ).value =
    song.title || "";


  document.getElementById(
    "editArtist"
  ).value =
    song.artist ||
    "Madushanka";


  document.getElementById(
    "editLanguage"
  ).value =
    song.language ||
    "Sinhala";


  document.getElementById(
    "editGenre"
  ).value =
    song.genre ||
    "Romantic";


  document.getElementById(
    "editMood"
  ).value =
    song.mood || "";


  document.getElementById(
    "editDescription"
  ).value =
    song.description || "";


  document.getElementById(
    "editLyrics"
  ).value =
    song.lyrics || "";


  document.getElementById(
    "editReleaseDate"
  ).value =
    song.release_date || "";


  const preview =
    document.getElementById(
      "editCoverPreview"
    );


  preview.src =
    song.cover_url || "";


  editStatus.className = "";

  editStatus.textContent = "";


  /*
    Remove old progress box when opening
    another edit.
  */

  const oldProgress =
    editForm.querySelector(
      ".hh-progress"
    );

  if (oldProgress) {
    oldProgress.remove();
  }


  editPanel.classList.remove(
    "hidden"
  );


  editPanel.scrollIntoView({
    behavior: "smooth",
    block: "start"
  });

}


/* =========================================================
   CANCEL EDIT
========================================================= */

cancelEdit.onclick = () => {

  editPanel.classList.add(
    "hidden"
  );

  editForm.reset();

  editStatus.className = "";

  editStatus.textContent = "";


  const progress =
    editForm.querySelector(
      ".hh-progress"
    );

  if (progress) {
    progress.remove();
  }

};


/* =========================================================
   SAVE EDIT
========================================================= */

editForm.onsubmit = async (e) => {

  e.preventDefault();


  const id =
    document.getElementById(
      "editId"
    ).value;


  editStatus.className = "";

  editStatus.textContent = "";


  const submitButton =
    editForm.querySelector(
      'button[type="submit"]'
    );


  const hasAudio =
    editForm.querySelector(
      'input[name="audio"]'
    )?.files?.length > 0;


  const hasCover =
    editForm.querySelector(
      'input[name="cover"]'
    )?.files?.length > 0;


  /*
    Only show real progress when a file
    is being uploaded. For text-only edits,
    we still show the publishing stage.
  */

  const progress =
    createProgressUI(
      editForm,
      "edit"
    );


  editForm.classList.add(
    "hh-uploading"
  );


  submitButton.disabled = true;

  submitButton.dataset.originalText =
    submitButton.textContent;

  submitButton.textContent =
    "Saving…";


  updateProgress(
    editForm,
    0,
    "Preparing changes",
    "Preparing your changes…"
  );


  try {

    const formData =
      new FormData(editForm);


    updateProgress(
      editForm,
      0,
      "Saving changes",
      "Starting secure update…"
    );


    const result =
      await uploadWithProgress(
        "/api/songs/" + id,
        "PUT",
        formData,
        (percent) => {

          const displayPercent =
            Math.min(
              95,
              percent * 0.95
            );


          updateProgress(
            editForm,
            displayPercent,
            "Uploading changes",
            hasAudio || hasCover
              ? `Uploading replacement files… ${Math.round(percent)}%`
              : "Saving song information…"
          );

        }
      );


    if (!result.ok) {

      throw new Error(
        result.data?.error ||
        "Update failed."
      );
    }


    updateProgress(
      editForm,
      97,
      "Finalizing",
      "Saving changes and updating the song…"
    );


    await new Promise(
      resolve =>
        setTimeout(
          resolve,
          250
        )
    );


    finishProgress(
      editForm,
      true,
      "Your changes have been saved successfully."
    );


    editStatus.className =
      "success";

    editStatus.textContent =
      "Changes saved successfully.";


    await load();


    setTimeout(() => {

      editPanel.classList.add(
        "hidden"
      );

      editForm.reset();

      editStatus.textContent = "";

      const progress =
        editForm.querySelector(
          ".hh-progress"
        );

      if (progress) {
        progress.remove();
      }

    }, 900);


  } catch (error) {

    console.error(error);


    finishProgress(
      editForm,
      false,
      error.message ||
      "Something went wrong."
    );


    editStatus.className =
      "error";

    editStatus.textContent =
      error.message ||
      "Something went wrong. Please try again.";

  } finally {

    editForm.classList.remove(
      "hh-uploading"
    );

    submitButton.disabled = false;

    submitButton.textContent =
      submitButton.dataset.originalText ||
      "Save Changes";
  }

};


/* =========================================================
   DELETE SONG
========================================================= */

async function deleteSong(id) {

  const song =
    songs.find(
      s =>
        Number(s.id) ===
        Number(id)
    );


  const title =
    song?.title ||
    "this song";


  if (
    !confirm(
      `Delete "${title}" and its files?`
    )
  ) {
    return;
  }


  try {

    const response =
      await fetch(
        "/api/songs/" + id,
        {
          method: "DELETE"
        }
      );


    const data =
      await response.json();


    if (!response.ok) {

      alert(
        data.error ||
        "Delete failed."
      );

      return;
    }


    await load();


  } catch (error) {

    console.error(error);

    alert(
      "Something went wrong while deleting."
    );

  }

}


/* =========================================================
   ESCAPE HTML
========================================================= */

function esc(value) {

  return String(
    value || ""
  )
    .replace(
      /[&<>"']/g,
      m =>
        ({
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          '"': "&quot;",
          "'": "&#039;"
        }[m])
    );

}


/* =========================================================
   START
========================================================= */

load();
