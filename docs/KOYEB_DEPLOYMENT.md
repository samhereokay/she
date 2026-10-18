# Production Deployment Guide: Hosting SHE on Koyeb

This guide provides step-by-step instructions for deploying the **SHE Anti-Theft Backend** to [Koyeb](https://www.koyeb.com) for 24/7 global production hosting with automatic SSL, custom domains, and zero maintenance.

---

## 1. Why Koyeb for SHE?
* **Zero Commercial API Keys**: Entirely self-hosted FastAPI backend with native WebSockets.
* **Global Anycast Edge & HTTPS**: Automatic TLS certificate provisioning on `https://<your-app>.koyeb.app`.
* **Managed PostgreSQL**: Free-tier managed database or native compatibility with Neon, Supabase, and AWS RDS.
* **Persistent WebSockets**: Full native WebSocket connection support for live tracking radar (`/api/v1/ws/devices/{id}`).

---

## 2. Prerequisites
1. Free account at [koyeb.com](https://www.koyeb.com).
2. The latest commits pushed to your GitHub repository:
   ```bash
   git push origin main
   ```

---

## 3. Step-by-Step Deployment via Koyeb Web Console

### Step 1: Provision Managed PostgreSQL (Recommended)
1. In the Koyeb Console, click **"Create"** $\rightarrow$ select **"Database"**.
2. Select **PostgreSQL**, choose a region closest to you (e.g., Frankfurt, Washington D.C., or Singapore).
3. Choose the **Free Instance (512MB / 1GB storage)**.
4. Name the database (e.g. `she-db`) and click **"Create Database"**.
5. Copy the **Connection String** (e.g., `postgres://user:password@ep-xyz.koyeb.app/koyebdb`).

### Step 2: Create the Web Service
1. In the Koyeb Console, click **"Create"** $\rightarrow$ select **"Service"**.
2. Choose **GitHub** as the deployment source.
3. Select your repository: `samhereokay/she`, branch: `main`.
4. Builder Configuration:
   * Select **Dockerfile**.
   * Koyeb will automatically detect the root `Dockerfile` and expose port `8000`.

### Step 3: Configure Environment Variables
In the **Environment Variables** section, add the following key-value pairs:

| Key | Example Value | Description |
| :--- | :--- | :--- |
| `SECRET_KEY` | *(64-character hex string)* | Signs JWT session tokens |
| `TOTP_ENCRYPTION_KEY` | *(64-character hex string)* | Encrypts 2FA secrets at rest |
| `DATABASE_URL` | `postgresql://...` | Connection URI from Step 1 |
| `ALLOW_PUBLIC_REGISTRATION` | `true` | Allows new owner accounts to register |
| `ENFORCE_HTTPS` | `true` | Injects HSTS and enforces secure TLS |
| `PROJECT_NAME` | `SHE Anti-Theft` | API title in docs and headers |

> **To generate new random 64-char keys**, run in terminal:
> ```bash
> python3 -c 'import secrets; print("SECRET_KEY=" + secrets.token_hex(32)); print("TOTP_ENCRYPTION_KEY=" + secrets.token_hex(32))'
> ```

### Step 4: Configure Health Check
* **Protocol**: `HTTP`
* **Path**: `/health`
* **Port**: `8000`

### Step 5: Deploy
Click **"Deploy Service"**. Koyeb will:
1. Build the multi-stage Docker container.
2. Auto-initialize the PostgreSQL tables via SQLAlchemy `Base.metadata.create_all`.
3. Assign a live public URL: `https://<service-name>-<org>.koyeb.app`.

---

## 4. Alternative: Deploying via Koyeb CLI

If you prefer using the terminal, Koyeb CLI is already installed at `/home/sam/.koyeb/bin/koyeb`:

```bash
# 1. Log in to your Koyeb account
/home/sam/.koyeb/bin/koyeb auth login

# 2. Deploy service directly from GitHub
/home/sam/.koyeb/bin/koyeb service create she-backend \
  --app she-anti-theft \
  --git github.com/samhereokay/she \
  --git-branch main \
  --git-build-command "Dockerfile" \
  --ports 8000:http \
  --routes /:8000 \
  --checks 8000:http:/health \
  --env ALLOW_PUBLIC_REGISTRATION=true \
  --env ENFORCE_HTTPS=true \
  --env SECRET_KEY="<YOUR_SECRET_KEY>" \
  --env TOTP_ENCRYPTION_KEY="<YOUR_TOTP_KEY>" \
  --env DATABASE_URL="<YOUR_POSTGRES_URL>"
```

---

## 5. Connecting Your Android Handsets to Koyeb

Once deployed, update the server URL in your Android applications:

1. **SHE Admin Companion**:
   * On the login screen, enter your Koyeb URL in the Server URL field:
     `https://<your-service>-<org>.koyeb.app`
   * Tap **Register** or **Sign In**.
2. **SHE Guardian**:
   * Scan the pairing QR code from the Admin Companion. The QR code automatically encapsulates the Koyeb HTTPS domain, pairing the phone over global mobile data instantly.
