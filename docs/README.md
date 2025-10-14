# CHDBOY Documentation

This directory contains the documentation for the CHDBOY Android application, which is automatically deployed to GitHub Pages.

## Structure

- `index.md` - Main landing page with privacy policy overview
- `policy.md` - Detailed privacy policy and terms of service
- `_config.yml` - Jekyll configuration for GitHub Pages
- `_layouts/` - Jekyll layout templates

## Deployment

The documentation is automatically deployed to GitHub Pages when changes are pushed to the master branch. The deployment workflow is defined in `.github/workflows/pages.yml`.

## Local Development

To test the documentation locally:

1. Install Jekyll and Bundler:
   ```bash
   gem install jekyll bundler
   ```

2. Navigate to the docs directory:
   ```bash
   cd docs
   ```

3. Install dependencies:
   ```bash
   bundle install
   ```

4. Run the local server:
   ```bash
   bundle exec jekyll serve
   ```

5. Visit `http://localhost:4000/CHDBOY/` in your browser
